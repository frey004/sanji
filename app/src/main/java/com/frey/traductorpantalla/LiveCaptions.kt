package com.frey.traductorpantalla

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Ajustes y modelos de voz (Vosk) de los subtítulos de directos. */
object LiveCaptions {
    /** Modelo pequeño de Vosk para cada idioma que se puede escuchar. */
    private val models = mapOf(
        TranslateLanguage.ENGLISH to "vosk-model-small-en-us-0.15",
        TranslateLanguage.JAPANESE to "vosk-model-small-ja-0.22",
        TranslateLanguage.CHINESE to "vosk-model-small-cn-0.22",
        TranslateLanguage.KOREAN to "vosk-model-small-ko-0.22",
        TranslateLanguage.FRENCH to "vosk-model-small-fr-0.22",
        TranslateLanguage.GERMAN to "vosk-model-small-de-0.15",
        TranslateLanguage.ITALIAN to "vosk-model-small-it-0.22",
        TranslateLanguage.PORTUGUESE to "vosk-model-small-pt-0.3",
        TranslateLanguage.SPANISH to "vosk-model-small-es-0.42",
        TranslateLanguage.VIETNAMESE to "vosk-model-small-vn-0.4",
        TranslateLanguage.HINDI to "vosk-model-small-hi-0.22",
    )

    val sources = Languages.sources.filter { it.code in models }

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

    /** Cuántos idiomas se escuchan a la vez en modo automático (cada uno gasta CPU y memoria). */
    const val MAX_AUTO_LANGUAGES = 3

    /** Modo «Detectar automáticamente»: escucha varios idiomas y elige el que mejor entiende. */
    fun autoDetect(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", false)

    fun saveAutoDetect(context: Context, auto: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", auto).apply()
    }

    /** Idiomas entre los que elige el modo automático. */
    fun candidates(context: Context): List<Language> {
        val codes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("candidates", null)
            ?: setOf(TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE, TranslateLanguage.KOREAN)
        return sources.filter { it.code in codes }
    }

    fun saveCandidates(context: Context, languages: List<Language>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("candidates", languages.map { it.code }.toSet())
            .apply()
    }

    /** Los idiomas que hay que escuchar con los ajustes actuales. */
    fun languagesToListen(context: Context): List<Language> =
        if (autoDetect(context)) candidates(context) else listOf(source(context))

    /** true: escucha el micrófono; false: el audio de las apps (Android 10+). */
    fun useMicrophone(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("microphone", false)

    fun saveUseMicrophone(context: Context, microphone: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("microphone", microphone).apply()
    }

    fun modelDir(context: Context, language: Language) = File(context.filesDir, "vosk/${models.getValue(language.code)}")

    fun isModelReady(context: Context, language: Language) = File(modelDir(context, language), READY_MARK).exists()

    /** Descarga y descomprime el modelo de voz (~40-80 MB, solo la primera vez). */
    suspend fun downloadModel(context: Context, language: Language, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val name = models.getValue(language.code)
            val dir = modelDir(context, language)
            dir.deleteRecursively()
            dir.mkdirs()
            val root = dir.canonicalPath + File.separator
            val connection = URL("https://alphacephei.com/vosk/models/$name.zip").openConnection() as HttpURLConnection
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
                ZipInputStream(counting).use { zip ->
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
                File(dir, READY_MARK).createNewFile()
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            } finally {
                connection.disconnect()
            }
        }
}
