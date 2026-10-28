package com.frey.traductorpantalla

import android.Manifest
import android.app.AlertDialog
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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        /** Solo se busca actualización automáticamente una vez cada vez que se abre la app. */
        private var checkedForUpdate = false
    }

    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private var askedNotifications = false
    /** Si el último botón pulsado fue el del modo Uma Musume. */
    private var startingGame = false
    /** APK ya descargado que espera a que se conceda el permiso de instalar apps. */
    private var pendingApk: File? = null
    private var updating = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslatorService.EXTRA_DATA, data)
                    .putExtra(TranslatorService.EXTRA_GAME, startingGame)
                ContextCompat.startForegroundService(this, intent)
                val game = if (startingGame) UmaMusume.launchIntent(this) else null
                if (game != null) {
                    statusText.text = "Modo Uma Musume activo."
                    startActivity(game)
                } else {
                    statusText.text = if (startingGame) {
                        "Modo Uma Musume activo. Abre el juego."
                    } else {
                        "Burbuja activa. Abre la app que quieras traducir."
                    }
                    moveTaskToBack(true)
                }
            } else {
                statusText.text = "Hace falta el permiso de captura de pantalla."
            }
            setStartButtonsEnabled(true)
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onStartClicked(startingGame) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceSpinner = findViewById(R.id.sourceSpinner)
        targetSpinner = findViewById(R.id.targetSpinner)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)

        setupSpinner(sourceSpinner, Languages.sources, Languages.source(this))
        setupSpinner(targetSpinner, Languages.targets, Languages.target(this))

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val openWeb = {
            val url = urlInput.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Escribe o pega un enlace", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, WebTranslateActivity::class.java).putExtra(WebTranslateActivity.EXTRA_URL, url))
            }
        }
        findViewById<Button>(R.id.openWebButton).setOnClickListener { openWeb() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) openWeb()
            actionId == EditorInfo.IME_ACTION_GO
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
                UmaMusume.saveOpacity(this@MainActivity, seekBar.progress)
                refreshRunningService()
            }
        })
        findViewById<Button>(R.id.umaStartButton).setOnClickListener { onStartClicked(game = true) }

        startButton.setOnClickListener { onStartClicked(game = false) }

        findViewById<TextView>(R.id.versionText).text = getString(R.string.update_version, BuildConfig.VERSION_NAME)
        findViewById<Button>(R.id.updateButton).setOnClickListener { checkForUpdate(manual = true) }
        if (!checkedForUpdate) {
            checkedForUpdate = true
            checkForUpdate(manual = false)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            if (TranslatorService.isRunning) {
                startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            }
            statusText.text = "Detenido."
        }
    }

    override fun onResume() {
        super.onResume()
        // Vuelve de los ajustes de «Instalar apps desconocidas».
        pendingApk?.let { apk ->
            if (Updater.canInstall(this)) {
                pendingApk = null
                Updater.install(this, apk)
            }
        }
        if (TranslatorService.isRunning) {
            statusText.text = if (TranslatorService.gameMode) "Modo Uma Musume activo." else "Burbuja activa."
        }
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
        refreshRunningService()
    }

    /** Si la burbuja ya está activa, aplica los ajustes nuevos sin pedir permisos de nuevo. */
    private fun refreshRunningService() {
        if (TranslatorService.isRunning) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_UPDATE_LANGUAGES))
        }
    }

    private fun setStartButtonsEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled
        findViewById<Button>(R.id.umaStartButton).isEnabled = enabled
    }

    private fun onStartClicked(game: Boolean) {
        startingGame = game
        if (TranslatorService.isRunning) {
            if (TranslatorService.gameMode != game) {
                Toast.makeText(this, "Pulsa Detener antes de cambiar de modo", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "La burbuja ya está activa", Toast.LENGTH_SHORT).show()
            val gameIntent = if (game) UmaMusume.launchIntent(this) else null
            if (gameIntent != null) startActivity(gameIntent) else moveTaskToBack(true)
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

        setStartButtonsEnabled(false)
        lifecycleScope.launch {
            val source = if (game) UmaMusume.source else selectedSource()
            val translator = Languages.translator(source, selectedTarget())
            if (translator != null) {
                statusText.text = "Descargando el idioma (solo la primera vez)…"
                try {
                    Languages.downloadModel(translator)
                } catch (e: Exception) {
                    statusText.text = "No se pudo descargar el idioma. Revisa tu conexión.\n${e.message}"
                    setStartButtonsEnabled(true)
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

    // ---------------------------------------------------------------- actualizaciones

    private fun checkForUpdate(manual: Boolean) {
        if (updating) return
        val button = findViewById<Button>(R.id.updateButton)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val release = Updater.checkForUpdate()
                when {
                    release != null -> offerUpdate(release)
                    manual -> Toast.makeText(this@MainActivity, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) {
                    Toast.makeText(this@MainActivity, "No se pudo comprobar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun offerUpdate(release: Release) {
        if (isFinishing) return
        val message = buildString {
            append("Tienes la ${BuildConfig.VERSION_NAME}. ¿Descargar e instalar la ${release.versionName}?")
            if (release.notes.isNotEmpty()) append("\n\n").append(release.notes)
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva versión disponible")
            .setMessage(message)
            .setPositiveButton("Actualizar") { _, _ -> downloadUpdate(release) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun downloadUpdate(release: Release) {
        updating = true
        val button = findViewById<Button>(R.id.updateButton)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@MainActivity, release) { percent ->
                    statusText.text = if (percent >= 0) "Descargando actualización… $percent %" else "Descargando actualización…"
                }
                statusText.text = "Actualización descargada."
                if (Updater.canInstall(this@MainActivity)) {
                    Updater.install(this@MainActivity, apk)
                } else {
                    pendingApk = apk
                    Toast.makeText(
                        this@MainActivity,
                        "Permite «Instalar apps desconocidas» para esta app y vuelve atrás",
                        Toast.LENGTH_LONG,
                    ).show()
                    startActivity(Updater.installPermissionIntent(this@MainActivity))
                }
            } catch (e: Exception) {
                statusText.text = "No se pudo descargar la actualización: ${e.message}"
            } finally {
                updating = false
                button.isEnabled = true
            }
        }
    }
}
