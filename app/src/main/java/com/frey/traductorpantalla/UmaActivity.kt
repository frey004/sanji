package com.frey.traductorpantalla

import android.graphics.Color
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Pantalla del modo dedicado a Uma Musume. */
class UmaActivity : BubbleActivity() {

    override val gameMode = true
    override fun sourceLanguage() = UmaMusume.source

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uma)
        setupBubbleControls()

        setupHeader(
            R.string.tile_uma_title, R.string.uma_subtitle, R.drawable.header_uma,
            Color.rgb(255, 111, 165), image = R.drawable.uma_musume,
        )

        setupLanguageSpinner(findViewById<Spinner>(R.id.targetSpinner), Languages.targets, Languages.target(this)) {
            if (it == Languages.target(this)) return@setupLanguageSpinner
            Languages.save(this, Languages.source(this), it)
            refreshRunningService()
        }

        val opacityLabel = findViewById<TextView>(R.id.umaOpacityLabel)
        val opacity = findViewById<SeekBar>(R.id.umaOpacity)
        opacity.progress = UmaMusume.opacity(this)
        opacityLabel.text = getString(R.string.uma_opacity_label, opacity.progress)
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                opacityLabel.text = getString(R.string.uma_opacity_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                UmaMusume.saveOpacity(this@UmaActivity, seekBar.progress)
                refreshRunningService()
            }
        })
    }
}
