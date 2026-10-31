package com.frey.traductorpantalla

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.graphics.Color
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
import android.widget.Switch
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
        setupHeader(
            R.string.tile_live_title, R.string.live_subtitle, R.drawable.header_live,
            Color.rgb(45, 43, 58), emoji = "🎙",
        )
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)

        setupSourceSpinner()
        setupSpinner(findViewById(R.id.targetSpinner), Languages.targets, Languages.target(this)) {
            if (it != Languages.target(this)) Languages.save(this, Languages.source(this), it)
        }

        val online = findViewById<Switch>(R.id.onlineSwitch)
        online.isChecked = Languages.onlineTranslation(this)
        online.setOnCheckedChangeListener { _, checked -> Languages.saveOnlineTranslation(this, checked) }

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
        // El interruptor de traducción en línea también está en la pantalla de inicio.
        findViewById<Switch>(R.id.onlineSwitch).isChecked = Languages.onlineTranslation(this)
        if (LiveCaptionService.isRunning) statusText.text = "Subtítulos activos."
    }

    /** Idioma del directo: «Detectar automáticamente» o uno concreto. */
    private fun setupSourceSpinner() {
        val spinner = findViewById<Spinner>(R.id.sourceSpinner)
        val autoPanel = findViewById<View>(R.id.autoPanel)
        val names = listOf(getString(R.string.live_auto)) + LiveCaptions.sources.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val auto = LiveCaptions.autoDetect(this)
        spinner.setSelection(if (auto) 0 else LiveCaptions.sources.indexOf(LiveCaptions.source(this)) + 1)
        autoPanel.visibility = if (auto) View.VISIBLE else View.GONE
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                LiveCaptions.saveAutoDetect(this@LiveActivity, position == 0)
                if (position > 0) LiveCaptions.saveSource(this@LiveActivity, LiveCaptions.sources[position - 1])
                autoPanel.visibility = if (position == 0) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        startButton.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!LiveCaptions.isSpeechModelReady(this@LiveActivity)) {
                    val message = "Descargando el reconocimiento de voz (solo la primera vez)…"
                    statusText.text = message
                    LiveCaptions.downloadSpeechModel(this@LiveActivity) { percent ->
                        runOnUiThread { statusText.text = "$message $percent %" }
                    }
                }
                for (source in LiveCaptions.possibleSources(this@LiveActivity)) {
                    Languages.translator(source, Languages.target(this@LiveActivity))?.let { translator ->
                        statusText.text = "Descargando la traducción desde ${source.name} (solo la primera vez)…"
                        try {
                            Languages.downloadModel(translator)
                        } finally {
                            translator.close()
                        }
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
