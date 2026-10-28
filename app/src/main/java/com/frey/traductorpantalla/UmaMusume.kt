package com.frey.traductorpantalla

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage

/** Ajustes y filtros del modo dedicado a Uma Musume. */
object UmaMusume {
    val versions = listOf(
        Language("Japonés (versión japonesa)", TranslateLanguage.JAPANESE, Script.JAPANESE),
        Language("Inglés (versión global)", TranslateLanguage.ENGLISH),
    )

    /** Versión japonesa y versión global del juego. */
    private val packages = listOf("jp.co.cygames.umamusume", "com.cygames.umamusume")

    private const val PREFS = "uma_musume"
    const val DEFAULT_OPACITY = 75

    fun source(context: Context): Language {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("version", TranslateLanguage.JAPANESE)
        return versions.firstOrNull { it.code == code } ?: versions[0]
    }

    fun saveSource(context: Context, source: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("version", source.code)
            .apply()
    }

    /** Opacidad del fondo de los subtítulos, de 0 (transparente) a 100. */
    fun opacity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("opacity", DEFAULT_OPACITY)

    fun saveOpacity(context: Context, opacity: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("opacity", opacity)
            .apply()
    }

    fun launchIntent(context: Context): Intent? =
        packages.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

    /**
     * Deja pasar diálogos, opciones y menús, y descarta lo demás: estadísticas,
     * contadores, monedas, niveles, horas…
     */
    fun isGameText(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        val digits = text.count { it.isDigit() }
        return letters >= 2 && digits * 2 <= letters
    }

    /** La caja de diálogo ocupa casi todo el ancho de la parte de abajo de la pantalla. */
    fun isDialogue(bounds: Rect, screenWidth: Int, screenHeight: Int): Boolean =
        bounds.top > screenHeight * 0.55f && bounds.width() > screenWidth * 0.5f
}
