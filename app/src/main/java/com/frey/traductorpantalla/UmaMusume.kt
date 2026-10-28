package com.frey.traductorpantalla

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage

/** Ajustes y filtros del modo dedicado a Uma Musume. */
object UmaMusume {
    /** El juego se usa en su versión japonesa. */
    val source = Language("Japonés", TranslateLanguage.JAPANESE, Script.JAPANESE)

    private const val PACKAGE = "jp.co.cygames.umamusume"

    private const val PREFS = "uma_musume"
    const val DEFAULT_OPACITY = 75

    /** Opacidad del fondo de los subtítulos, de 0 (transparente) a 100. */
    fun opacity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("opacity", DEFAULT_OPACITY)

    fun saveOpacity(context: Context, opacity: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("opacity", opacity)
            .apply()
    }

    fun launchIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

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
