package com.frey.traductorpantalla

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Navegador que traduce las páginas web en el propio móvil: el script
 * web_translator.js extrae los textos, aquí se traducen con ML Kit y se
 * devuelven a la página para sustituir el original.
 */
class WebTranslateActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var script: String
    private var translator: Translator? = null
    private val modelLock = Mutex()
    private var modelReady = false
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }

    /** Lotes de textos pendientes, etiquetados con la página de la que vienen. */
    private val queue = Channel<Pair<Int, JSONArray>>(Channel.UNLIMITED)
    private var worker: Job? = null
    /** Cambia en cada navegación para descartar traducciones de la página anterior. */
    @Volatile
    private var pageId = 0
    private var pending = 0
    private var showOriginal = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        toggleButton = findViewById(R.id.toggleButton)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.webStatus)
        script = assets.open("web_translator.js").bufferedReader().use { it.readText() }

        val source = Languages.source(this)
        val target = Languages.target(this)
        translator = Languages.translator(source, target)
        statusText.text = if (translator == null) {
            "El idioma de origen y el de destino son iguales: no se traducirá."
        } else {
            "${source.name} → ${target.name}"
        }
        if (translator == null) toggleButton.visibility = View.GONE

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.addJavascriptInterface(Bridge(), "TrBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") return false
                // Enlaces a otras apps (mailto:, tel:, intent:…).
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageId++
                pending = 0
                showOriginal = false
                toggleButton.text = getString(R.string.web_show_original)
                urlInput.setText(url)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (translator != null) view.evaluateJavascript(script, null)
                updateProgress()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }

        findViewById<Button>(R.id.goButton).setOnClickListener { openTyped() }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                openTyped()
                true
            } else {
                false
            }
        }
        toggleButton.setOnClickListener {
            showOriginal = !showOriginal
            webView.evaluateJavascript("window.__tr && window.__tr.setOriginal($showOriginal)", null)
            toggleButton.text = getString(if (showOriginal) R.string.web_show_translation else R.string.web_show_original)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        worker = lifecycleScope.launch { for ((page, batch) in queue) translateBatch(page, batch) }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            linkFrom(intent)?.let { load(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        worker?.cancel()
        queue.close()
        translator?.close()
        webView.destroy()
        super.onDestroy()
    }

    /** Enlace recibido desde la pantalla principal o compartido desde otra app. */
    private fun linkFrom(intent: Intent): String? {
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        if (intent.action == Intent.ACTION_VIEW) return intent.dataString
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            // Al compartir, muchas apps añaden el título antes del enlace.
            return Regex("https?://\\S+").find(text)?.value ?: text
        }
        return null
    }

    private fun openTyped() {
        val text = urlInput.text.toString()
        if (text.isBlank()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()
        load(text)
    }

    private fun load(input: String) {
        webView.loadUrl(normalizeUrl(input))
    }

    // ---------------------------------------------------------------- traducción

    private inner class Bridge {
        /** Lo llama el script desde un hilo del WebView. */
        @JavascriptInterface
        fun translate(json: String) {
            val batch = runCatching { JSONArray(json) }.getOrNull() ?: return
            val page = pageId
            runOnUiThread {
                if (page != pageId) return@runOnUiThread
                pending++
                updateProgress()
                queue.trySend(page to batch)
            }
        }
    }

    private suspend fun ensureModel(translator: Translator) = modelLock.withLock {
        if (modelReady) return@withLock
        statusText.text = "Descargando el idioma (solo la primera vez)…"
        Languages.downloadModel(translator)
        modelReady = true
        statusText.text = "${Languages.source(this).name} → ${Languages.target(this).name}"
    }

    private suspend fun translateBatch(page: Int, batch: JSONArray) {
        try {
            val current = translator ?: return
            if (page != pageId) return
            ensureModel(current)
            val results = JSONArray()
            for (i in 0 until batch.length()) {
                if (page != pageId) return
                val item = batch.getJSONObject(i)
                val text = item.getString("text")
                val translated = cache[text] ?: current.translate(text).await().also { cache[text] = it }
                results.put(JSONObject().put("id", item.getInt("id")).put("text", translated))
            }
            if (page == pageId) webView.evaluateJavascript("window.__tr && window.__tr.apply($results)", null)
        } catch (e: Exception) {
            if (page == pageId) statusText.text = "No se pudo traducir: ${e.message ?: "revisa tu conexión"}"
        } finally {
            if (page == pageId) {
                pending--
                updateProgress()
            }
        }
    }

    private fun updateProgress() {
        val loading = progress.progress < 100
        progress.visibility = if (loading || pending > 0) View.VISIBLE else View.GONE
        progress.isIndeterminate = !loading && pending > 0
    }
}

/** Convierte lo escrito en una URL: añade https:// o, si no parece un enlace, lo busca en Google. */
fun normalizeUrl(input: String): String {
    val text = input.trim()
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) return text
    if (!text.contains(' ') && text.contains('.')) return "https://$text"
    return "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
}
