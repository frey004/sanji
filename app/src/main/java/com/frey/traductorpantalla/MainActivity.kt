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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private var askedNotifications = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslatorService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                statusText.text = "Burbuja activa. Abre la app que quieras traducir."
                moveTaskToBack(true)
            } else {
                statusText.text = "Hace falta el permiso de captura de pantalla."
            }
            startButton.isEnabled = true
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onStartClicked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceSpinner = findViewById(R.id.sourceSpinner)
        targetSpinner = findViewById(R.id.targetSpinner)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)

        setupSpinner(sourceSpinner, Languages.sources, Languages.source(this))
        setupSpinner(targetSpinner, Languages.targets, Languages.target(this))

        startButton.setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            if (TranslatorService.isRunning) {
                startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            }
            statusText.text = "Detenido."
        }
    }

    override fun onResume() {
        super.onResume()
        if (TranslatorService.isRunning) statusText.text = "Burbuja activa."
    }

    private fun setupSpinner(spinner: Spinner, items: List<Language>, selected: Language) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        spinner.setSelection(items.indexOf(selected))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = saveLanguages()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectedSource() = Languages.sources[sourceSpinner.selectedItemPosition]
    private fun selectedTarget() = Languages.targets[targetSpinner.selectedItemPosition]

    private fun saveLanguages() {
        if (sourceSpinner.selectedItemPosition < 0 || targetSpinner.selectedItemPosition < 0) return
        val source = selectedSource()
        val target = selectedTarget()
        if (source == Languages.source(this) && target == Languages.target(this)) return
        Languages.save(this, source, target)
        if (TranslatorService.isRunning) {
            // La burbuja ya está activa: que cambie de idioma sin pedir permisos de nuevo.
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_UPDATE_LANGUAGES))
        }
    }

    private fun onStartClicked() {
        if (TranslatorService.isRunning) {
            Toast.makeText(this, "La burbuja ya está activa", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» y vuelve a pulsar Iniciar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
            val translator = Languages.translator(selectedSource(), selectedTarget())
            if (translator != null) {
                statusText.text = "Descargando el idioma (solo la primera vez)…"
                try {
                    Languages.downloadModel(translator)
                } catch (e: Exception) {
                    statusText.text = "No se pudo descargar el idioma. Revisa tu conexión.\n${e.message}"
                    startButton.isEnabled = true
                    return@launch
                } finally {
                    translator.close()
                }
            }
            statusText.text = ""
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
