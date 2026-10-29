package com.frey.traductorpantalla

import android.graphics.Color
import android.os.Bundle
import android.widget.Spinner

/** Pantalla de la traducción en vivo de cualquier app. */
class ScreenActivity : BubbleActivity() {

    override val gameMode = false
    override fun sourceLanguage() = Languages.source(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        setupHeader(
            R.string.tile_screen_title, R.string.screen_subtitle, R.drawable.header_screen,
            Color.rgb(0, 184, 148), emoji = "文A",
        )
        setupBubbleControls()

        setupLanguageSpinner(findViewById<Spinner>(R.id.sourceSpinner), Languages.sources, Languages.source(this)) {
            if (it == Languages.source(this)) return@setupLanguageSpinner
            Languages.save(this, it, Languages.target(this))
            if (!TranslatorService.gameMode) refreshRunningService()
        }
        setupLanguageSpinner(findViewById<Spinner>(R.id.targetSpinner), Languages.targets, Languages.target(this)) {
            if (it == Languages.target(this)) return@setupLanguageSpinner
            Languages.save(this, Languages.source(this), it)
            refreshRunningService()
        }
    }
}
