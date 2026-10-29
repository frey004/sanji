package com.frey.traductorpantalla

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

data class Language(val name: String, val code: String, val script: Script = Script.LATIN) {
    /** Los idiomas CJK no separan palabras con espacios al unir líneas. */
    val lineSeparator: String
        get() = if (script == Script.CHINESE || script == Script.JAPANESE) "" else " "
}

object Languages {
    /** Opción «Detectar automáticamente»: el idioma se averigua en cada texto. */
    val AUTO = Language("Detectar automáticamente", "auto")
    val JAPANESE = Language("Japonés", TranslateLanguage.JAPANESE, Script.JAPANESE)
    val KOREAN = Language("Coreano", TranslateLanguage.KOREAN, Script.KOREAN)

    val sources = listOf(
        AUTO,
        Language("Inglés", TranslateLanguage.ENGLISH),
        Language("Japonés", TranslateLanguage.JAPANESE, Script.JAPANESE),
        Language("Chino", TranslateLanguage.CHINESE, Script.CHINESE),
        Language("Coreano", TranslateLanguage.KOREAN, Script.KOREAN),
        Language("Francés", TranslateLanguage.FRENCH),
        Language("Alemán", TranslateLanguage.GERMAN),
        Language("Italiano", TranslateLanguage.ITALIAN),
        Language("Portugués", TranslateLanguage.PORTUGUESE),
        Language("Español", TranslateLanguage.SPANISH),
        Language("Indonesio", TranslateLanguage.INDONESIAN),
        Language("Vietnamita", TranslateLanguage.VIETNAMESE),
        Language("Hindi", TranslateLanguage.HINDI, Script.DEVANAGARI),
    )

    val targets = listOf(
        Language("Español", TranslateLanguage.SPANISH),
        Language("Inglés", TranslateLanguage.ENGLISH),
        Language("Portugués", TranslateLanguage.PORTUGUESE),
        Language("Francés", TranslateLanguage.FRENCH),
        Language("Alemán", TranslateLanguage.GERMAN),
        Language("Italiano", TranslateLanguage.ITALIAN),
        Language("Japonés", TranslateLanguage.JAPANESE, Script.JAPANESE),
        Language("Chino", TranslateLanguage.CHINESE, Script.CHINESE),
        Language("Coreano", TranslateLanguage.KOREAN, Script.KOREAN),
        Language("Ruso", TranslateLanguage.RUSSIAN),
    )

    private const val PREFS = "ajustes"

    fun source(context: Context): Language {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("source", AUTO.code)
        return sources.firstOrNull { it.code == code } ?: AUTO
    }

    fun target(context: Context): Language {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("target", TranslateLanguage.SPANISH)
        return targets.firstOrNull { it.code == code } ?: targets[0]
    }

    /** Nombre de un idioma a partir de su código (p. ej. «ja» → «Japonés»). */
    fun nameOf(code: String) = (sources + targets).firstOrNull { it.code == code }?.name ?: code

    /** Traducir con Google Translate en línea (mejor) y, si falla, sin conexión. Vale para todos los modos. */
    fun onlineTranslation(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("online", true)

    fun saveOnlineTranslation(context: Context, online: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("online", online).apply()
    }

    fun save(context: Context, source: Language, target: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("source", source.code)
            .putString("target", target.code)
            .apply()
    }

    fun recognizer(source: Language): TextRecognizer = when (source.script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /** Devuelve null si origen y destino coinciden (no hay nada que traducir). */
    fun translator(source: Language, target: Language): Translator? {
        // En automático el traductor se elige texto a texto (ver TextTranslator).
        if (source.code == target.code || source == AUTO) return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source.code)
            .setTargetLanguage(target.code)
            .build()
        return Translation.getClient(options)
    }

    suspend fun downloadModel(translator: Translator) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }
}
