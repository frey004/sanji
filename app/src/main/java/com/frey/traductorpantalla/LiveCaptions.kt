package com.frey.traductorpantalla

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Ajustes y modelos de voz de los subtítulos de directos: SenseVoice para
 * japonés, chino, coreano e inglés (mucho más preciso y detecta el idioma) y
 * Vosk para el resto.
 */
object LiveCaptions {
    /** Idiomas que reconoce SenseVoice; entre ellos elige el modo automático. */
    private val senseVoiceCodes = setOf(
        TranslateLanguage.JAPANESE,
        TranslateLanguage.CHINESE,
        TranslateLanguage.KOREAN,
        TranslateLanguage.ENGLISH,
    )
    // La versión de 2024: la de 2025-09-09 está ajustada al cantonés y confunde los demás idiomas.
    private const val SENSE_VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private val senseVoiceFiles = setOf("model.int8.onnx", "tokens.txt")

    /** Modelo pequeño de Vosk para los demás idiomas. */
    private val models = mapOf(
        TranslateLanguage.FRENCH to "vosk-model-small-fr-0.22",
        TranslateLanguage.GERMAN to "vosk-model-small-de-0.15",
        TranslateLanguage.ITALIAN to "vosk-model-small-it-0.22",
        TranslateLanguage.PORTUGUESE to "vosk-model-small-pt-0.3",
        TranslateLanguage.SPANISH to "vosk-model-small-es-0.42",
        TranslateLanguage.VIETNAMESE to "vosk-model-small-vn-0.4",
        TranslateLanguage.HINDI to "vosk-model-small-hi-0.22",
    )

    val sources = Languages.sources.filter { it.code in models || it.code in senseVoiceCodes }

    /** Idiomas entre los que distingue el modo automático. */
    val autoLanguages = sources.filter { it.code in senseVoiceCodes }

    private const val PREFS = "directos"
    private const val READY_MARK = ".listo"

    fun source(context: Context): Language {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("source", TranslateLanguage.ENGLISH)
        return sources.firstOrNull { it.code == code } ?: sources[0]
    }

    fun saveSource(context: Context, source: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("source", source.code).apply()
    }

    /** Modo «Detectar automáticamente» (japonés, chino, coreano o inglés). */
    fun autoDetect(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", false)

    fun saveAutoDetect(context: Context, auto: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", auto).apply()
    }

    /** Idiomas desde los que puede hacer falta traducir con los ajustes actuales. */
    fun possibleSources(context: Context): List<Language> =
        if (autoDetect(context)) autoLanguages else listOf(source(context))

    fun usesSenseVoice(context: Context) = autoDetect(context) || source(context).code in senseVoiceCodes

    /** Lo que hay que descargar antes de empezar: true si ya está todo. */
    fun isSpeechModelReady(context: Context) =
        if (usesSenseVoice(context)) isSenseVoiceReady(context) else isModelReady(context, source(context))

    suspend fun downloadSpeechModel(context: Context, onProgress: (Int) -> Unit) =
        if (usesSenseVoice(context)) downloadSenseVoice(context, onProgress) else downloadModel(context, source(context), onProgress)

    fun senseVoiceDir(context: Context) = File(context.filesDir, "sensevoice")

    private fun isSenseVoiceReady(context: Context) = File(senseVoiceDir(context), READY_MARK).exists()

    /** Traducir con Google Translate en línea (mejor) y, si falla, sin conexión. */
    fun onlineTranslation(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("online", true)

    fun saveOnlineTranslation(context: Context, online: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("online", online).apply()
    }

    /** true: escucha el micrófono; false: el audio de las apps (Android 10+). */
    fun useMicrophone(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("microphone", false)

    fun saveUseMicrophone(context: Context, microphone: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("microphone", microphone).apply()
    }

    fun modelDir(context: Context, language: Language) = File(context.filesDir, "vosk/${models.getValue(language.code)}")

    private fun isModelReady(context: Context, language: Language) = File(modelDir(context, language), READY_MARK).exists()

    /** Descarga SenseVoice (~170 MB, solo la primera vez) y guarda el modelo y sus tokens. */
    private suspend fun downloadSenseVoice(context: Context, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = senseVoiceDir(context)
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                download(SENSE_VOICE_URL, onProgress) { input ->
                    TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val entry = tar.nextEntry ?: break
                            val name = entry.name.substringAfterLast('/')
                            if (!entry.isDirectory && name in senseVoiceFiles) {
                                File(dir, name).outputStream().use { tar.copyTo(it) }
                            }
                        }
                    }
                }
                if (senseVoiceFiles.any { !File(dir, it).exists() }) throw IOException("el modelo descargado está incompleto")
                File(dir, READY_MARK).createNewFile()
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }

    /** Abre la descarga y avisa del porcentaje según se va leyendo. */
    private suspend fun download(url: String, onProgress: (Int) -> Unit, read: suspend (InputStream) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var lastPercent = -1
            val counting = object : FilterInputStream(connection.inputStream) {
                var done = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                    if (n <= 0 || total <= 0) return@also
                    done += n
                    val percent = (done * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            read(counting)
        } finally {
            connection.disconnect()
        }
    }

    /** Descarga y descomprime un modelo de Vosk (~40-80 MB, solo la primera vez). */
    private suspend fun downloadModel(context: Context, language: Language, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val name = models.getValue(language.code)
            val dir = modelDir(context, language)
            dir.deleteRecursively()
            dir.mkdirs()
            val root = dir.canonicalPath + File.separator
            try {
                download("https://alphacephei.com/vosk/models/$name.zip", onProgress) { input ->
                    ZipInputStream(input).use { zip ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val entry = zip.nextEntry ?: break
                            // El zip trae una carpeta con el nombre del modelo: se quita.
                            val relative = entry.name.substringAfter('/', "")
                            if (relative.isEmpty()) continue
                            val file = File(dir, relative)
                            // Nada puede escribirse fuera de la carpeta del modelo.
                            if (!file.canonicalPath.startsWith(root)) throw IOException("archivo no válido en el modelo")
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else {
                                file.parentFile?.mkdirs()
                                file.outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
                File(dir, READY_MARK).createNewFile()
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
}
