package com.frey.traductorpantalla

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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

/**
 * Base de las pantallas que lanzan la burbuja flotante (pantalla en vivo y
 * Uma Musume): permisos, descarga del idioma y arranque/parada del servicio.
 */
abstract class BubbleActivity : ComponentActivity() {

    /** true en el modo Uma Musume. */
    protected abstract val gameMode: Boolean
    /** Idioma del texto que se va a leer en pantalla. */
    protected abstract fun sourceLanguage(): Language

    protected lateinit var startButton: Button
    protected lateinit var statusText: TextView
    private var askedNotifications = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslatorService.EXTRA_DATA, data)
                    .putExtra(TranslatorService.EXTRA_GAME, gameMode)
                ContextCompat.startForegroundService(this, intent)
                statusText.text = activeMessage()
                openTargetApp()
            } else {
                statusText.text = "Hace falta el permiso de captura de pantalla."
            }
            startButton.isEnabled = true
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onStartClicked() }

    /** Enlaza los botones comunes; llamar desde onCreate tras setContentView. */
    protected fun setupBubbleControls() {
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)
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
        if (TranslatorService.isRunning && TranslatorService.gameMode == gameMode) statusText.text = activeMessage()
    }

    private fun activeMessage() = if (gameMode) "Modo Uma Musume activo." else "Burbuja activa."

    protected fun setupLanguageSpinner(
        spinner: Spinner,
        items: List<Language>,
        selected: Language,
        onSelected: (Language) -> Unit,
    ) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        spinner.setSelection(items.indexOf(selected))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onSelected(items[position])
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Si la burbuja ya está activa, aplica los ajustes nuevos sin pedir permisos de nuevo. */
    protected fun refreshRunningService() {
        if (TranslatorService.isRunning) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_UPDATE_LANGUAGES))
        }
    }

    /** En el modo Uma Musume abre el juego; si no, vuelve a la app anterior. */
    private fun openTargetApp() {
        val game = if (gameMode) UmaMusume.launchIntent(this) else null
        if (game != null) {
            startActivity(game)
        } else {
            if (gameMode) statusText.text = "Modo Uma Musume activo. Abre el juego."
            moveTaskToBack(true)
        }
    }

    private fun onStartClicked() {
        if (LiveCaptionService.isRunning) {
            Toast.makeText(this, "Detén primero los subtítulos de directos", Toast.LENGTH_LONG).show()
            return
        }
        if (TranslatorService.isRunning) {
            if (TranslatorService.gameMode != gameMode) {
                Toast.makeText(this, "Hay otro modo activo: pulsa Detener antes de cambiar", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "La burbuja ya está activa", Toast.LENGTH_SHORT).show()
            openTargetApp()
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
            val translator = Languages.translator(sourceLanguage(), Languages.target(this@BubbleActivity))
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
