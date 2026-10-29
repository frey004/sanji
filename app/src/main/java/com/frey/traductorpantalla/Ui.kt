package com.frey.traductorpantalla

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Rellena la cabecera de color de una pantalla (layout view_header): título,
 * explicación, icono y botón de volver, y pinta la barra de estado a juego.
 */
fun Activity.setupHeader(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    @DrawableRes background: Int,
    statusBarColor: Int,
    @DrawableRes image: Int? = null,
    emoji: String? = null,
) {
    findViewById<View>(R.id.header).setBackgroundResource(background)
    findViewById<TextView>(R.id.headerTitle).setText(title)
    findViewById<TextView>(R.id.headerSubtitle).setText(subtitle)
    findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
    if (image != null) {
        findViewById<ImageView>(R.id.headerImage).apply {
            setImageResource(image)
            clipToOutline = true
            visibility = View.VISIBLE
        }
    }
    if (emoji != null) {
        findViewById<TextView>(R.id.headerEmoji).apply {
            text = emoji
            visibility = View.VISIBLE
        }
    }
    window.statusBarColor = statusBarColor
}
