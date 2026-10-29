package com.frey.traductorpantalla

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Pantalla de inicio: un cuadro por cada modo, y las actualizaciones. */
class MainActivity : ComponentActivity() {

    companion object {
        /** Solo se busca actualización automáticamente una vez cada vez que se abre la app. */
        private var checkedForUpdate = false
    }

    private lateinit var statusText: TextView
    /** APK ya descargado que espera a que se conceda el permiso de instalar apps. */
    private var pendingApk: File? = null
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)

        findViewById<View>(R.id.tileUma).setOnClickListener { startActivity(Intent(this, UmaActivity::class.java)) }
        findViewById<View>(R.id.tileScreen).setOnClickListener { startActivity(Intent(this, ScreenActivity::class.java)) }
        findViewById<View>(R.id.tileWeb).setOnClickListener { startActivity(Intent(this, LinkActivity::class.java)) }
        UmaMusume.icon(this)?.let {
            findViewById<ImageView>(R.id.umaIcon).setImageDrawable(it)
            findViewById<View>(R.id.umaIconFallback).visibility = View.GONE
        }

        findViewById<TextView>(R.id.versionText).text = getString(R.string.update_version, BuildConfig.VERSION_NAME)
        findViewById<Button>(R.id.updateButton).setOnClickListener { checkForUpdate(manual = true) }
        if (!checkedForUpdate) {
            checkedForUpdate = true
            checkForUpdate(manual = false)
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
