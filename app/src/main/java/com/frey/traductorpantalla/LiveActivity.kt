package com.frey.traductorpantalla

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Pantalla de los subtítulos traducidos para directos y vídeos. */
class LiveActivity : ComponentActivity() {

    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private var askedNotifications = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startCaptions(
                    Intent(this, LiveCaptionService::class.java)
                        .putExtra(LiveCaptionService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(LiveCaptionService.EXTRA_DATA, data)
                )
            } else {
                statusText.text = "Hace falta el permiso para capturar el audio."
            }
            startButton.isEnabled = true
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onStartClicked() else statusText.text = "Hace falta el permiso de audio para escuchar el directo."
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onStartClicked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)

        setupSpinner(findViewById(R.id.sourceSpinner), LiveCaptions.sources, LiveCaptions.source(this)) {
            LiveCaptions.saveSource(this, it)
        }
        setupSpinner(findViewById(R.id.targetSpinner), Languages.targets, Languages.target(this)) {
            if (it != Languages.target(this)) Languages.save(this, Languages.source(this), it)
        }

        val appAudio = findViewById<RadioButton>(R.id.sourceAppAudio)
        val microphone = findViewById<RadioButton>(R.id.sourceMicrophone)
        if (Build.VERSION.SDK_INT < 29) {
            // Capturar el audio de otras apps solo es posible desde Android 10.
            appAudio.isEnabled = false
            LiveCaptions.saveUseMicrophone(this, true)
        }
        if (LiveCaptions.useMicrophone(this)) microphone.isChecked = true else appAudio.isChecked = true
        appAudio.setOnCheckedChangeListener { _, checked -> if (checked) LiveCaptions.saveUseMicrophone(this, false) }
        microphone.setOnCheckedChangeListener { _, checked -> if (checked) LiveCaptions.saveUseMicrophone(this, true) }

        startButton.setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            if (LiveCaptionService.isRunning) {
                startService(Intent(this, LiveCaptionService::class.java).setAction(LiveCaptionService.ACTION_STOP))
            }
            statusText.text = "Detenido."
        }
    }

    override fun onResume() {
        super.onResume()
        if (LiveCaptionService.isRunning) statusText.text = "Subtítulos activos."
    }

    private fun setupSpinner(spinner: Spinner, items: List<Language>, selected: Language, onSelected: (Language) -> Unit) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        spinner.setSelection(items.indexOf(selected))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onSelected(items[position])
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun onStartClicked() {
        if (LiveCaptionService.isRunning) {
            Toast.makeText(this, "Los subtítulos ya están activos: pulsa Detener para cambiar los ajustes", Toast.LENGTH_LONG).show()
            return
        }
        if (TranslatorService.isRunning) {
            Toast.makeText(this, "Detén primero la burbuja de traducción de pantalla", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» y vuelve a pulsar Iniciar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val source = LiveCaptions.source(this)
        startButton.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!LiveCaptions.isModelReady(this@LiveActivity, source)) {
                    statusText.text = "Descargando la voz en ${source.name} (solo la primera vez)…"
                    LiveCaptions.downloadModel(this@LiveActivity, source) { percent ->
                        runOnUiThread {
                            statusText.text = "Descargando la voz en ${source.name} (solo la primera vez)… $percent %"
                        }
                    }
                }
                Languages.translator(source, Languages.target(this@LiveActivity))?.let { translator ->
                    statusText.text = "Descargando el idioma de traducción (solo la primera vez)…"
                    try {
                        Languages.downloadModel(translator)
                    } finally {
                        translator.close()
                    }
                }
            } catch (e: Exception) {
                statusText.text = "No se pudo descargar. Revisa tu conexión.\n${e.message}"
                startButton.isEnabled = true
                return@launch
            }
            statusText.text = ""
            if (LiveCaptions.useMicrophone(this@LiveActivity)) {
                startCaptions(Intent(this@LiveActivity, LiveCaptionService::class.java))
                startButton.isEnabled = true
            } else {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    private fun startCaptions(intent: Intent) {
        ContextCompat.startForegroundService(this, intent.setAction(LiveCaptionService.ACTION_START))
        statusText.text = "Subtítulos activos. Abre el directo."
        moveTaskToBack(true)
    }
}
