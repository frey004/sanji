package com.frey.traductorpantalla

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Traductor común de la pantalla, Uma Musume, las webs y el manga.
 *
 * Con [Languages.AUTO] averigua el idioma de cada texto (ML Kit, sin
 * conexión), los agrupa por idioma y no toca los que ya están en el idioma
 * de destino. Traduce en línea con Google Translate si está activado y, si
 * no hay conexión o falla, con los modelos de ML Kit, que se descargan la
 * primera vez que hace falta cada idioma.
 */
class TextTranslator(
    private val context: Context,
    private val source: Language,
    private val target: Language,
) : Closeable {

    companion object {
        /** Los textos más cortos («OK», «Menú») se identifican mal: usan el idioma de los demás. */
        private const val MIN_CHARS_TO_IDENTIFY = 12
    }

    private val auto = source == Languages.AUTO
    private val identifier = if (auto) {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
    } else {
        null
    }
    private val offline = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val offlineLock = Mutex()
    private val cache = LruCache<String, String>(3000)

    /** Idioma de un texto (código de ML Kit) o null si no se sabe. */
    suspend fun detect(text: String): String? {
        val identifier = identifier ?: return source.code
        val tag = runCatching { identifier.identifyLanguage(text).await() }.getOrNull() ?: return null
        if (tag == "und") return null
        // «zh-Latn» (chino en pinyin), «ja-Latn»…: el idioma es lo de antes del guion.
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }

    suspend fun translate(text: String): String = translateAll(listOf(text))[0]

    /** Traduce varios textos y devuelve las traducciones en el mismo orden. */
    suspend fun translateAll(texts: List<String>): List<String> {
        if (texts.isEmpty()) return texts
        val dominant = if (auto) detect(texts.joinToString("\n").take(2000)) else source.code
        val languages = texts.map { text ->
            if (auto && text.length >= MIN_CHARS_TO_IDENTIFY) detect(text) ?: dominant else dominant
        }
        val result = texts.toMutableList()
        for ((code, indices) in texts.indices.groupBy { languages[it] }) {
            // Lo que ya está en el idioma de destino se queda como está.
            if (code == target.code) continue
            val translated = translateGroup(indices.map { texts[it] }, code)
            indices.forEachIndexed { k, i -> result[i] = translated[k] }
        }
        return result
    }

    private suspend fun translateGroup(texts: List<String>, code: String?): List<String> {
        val key = code ?: "auto"
        val out = texts.map { cache.get("$key\u0000$it") }.toMutableList()
        val missing = texts.indices.filter { out[it] == null }
        if (missing.isNotEmpty()) {
            val pending = missing.map { texts[it] }
            val translated = (if (Languages.onlineTranslation(context)) OnlineTranslator.translateAll(pending, key, target.code) else null)
                ?: translateOffline(pending, code)
            missing.forEachIndexed { k, i ->
                out[i] = translated[k]
                cache.put("$key\u0000${texts[i]}", translated[k])
            }
        }
        return out.map { it ?: "" }
    }

    /** Sin conexión: un modelo de ML Kit por idioma; si no se sabe el idioma, se deja el texto. */
    private suspend fun translateOffline(texts: List<String>, code: String?): List<String> {
        if (code == null || code == target.code) return texts
        val translator = offlineLock.withLock {
            val translator = offline.getOrPut(code) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(code).setTargetLanguage(target.code).build())
            }
            if (code !in ready) {
                runCatching { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                    .onFailure { return texts }
                ready += code
            }
            translator
        }
        return texts.map { runCatching { translator.translate(it).await() }.getOrDefault(it) }
    }

    override fun close() {
        identifier?.close()
        offline.values.forEach { it.close() }
    }
}
