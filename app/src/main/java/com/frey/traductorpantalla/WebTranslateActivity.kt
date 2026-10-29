package com.frey.traductorpantalla

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Lector que traduce páginas web en el propio móvil: el script
 * web_translator.js extrae los textos, aquí se traducen con ML Kit y se
 * devuelven a la página para sustituir el original. La página se recorre
 * por pantallas («Pág 01/02») y la traducción se puede descargar como texto.
 */
class WebTranslateActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var webView: WebView
    private lateinit var emptyText: TextView
    private lateinit var pageText: TextView
    private lateinit var toggleButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updatePage = Runnable { refreshPageNumber() }
    private var currentPage = 1
    private var totalPages = 1
    /** Texto que se va a guardar mientras el usuario elige dónde. */
    private var downloadText = ""

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { save(it) } }

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
        emptyText = findViewById(R.id.emptyText)
        pageText = findViewById(R.id.pageText)
        toggleButton = findViewById(R.id.toggleButton)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.webStatus)
        script = assets.open("web_translator.js").bufferedReader().use { it.readText() }
        // Esquinas redondeadas también para el contenido de la página.
        findViewById<View>(R.id.webFrame).clipToOutline = true
        loadLanguages()

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
                emptyText.visibility = View.GONE
                progress.visibility = View.VISIBLE
                showPage(1, 1)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (translator != null) view.evaluateJavascript(script, null)
                updateProgress()
                schedulePageUpdate()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }

        webView.setOnScrollChangeListener { _, _, _, _, _ -> schedulePageUpdate() }
        findViewById<View>(R.id.prevButton).setOnClickListener { goToPage(currentPage - 1) }
        findViewById<View>(R.id.nextButton).setOnClickListener { goToPage(currentPage + 1) }
        findViewById<View>(R.id.linkButton).setOnClickListener { showLinkDialog() }
        findViewById<View>(R.id.downloadButton).setOnClickListener { download() }
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
            if (webView.url != null) emptyText.visibility = View.GONE
        } else {
            // Desde el cuadro de inicio se llega sin enlace: se pide directamente.
            linkFrom(intent)?.let { load(it) } ?: showLinkDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(updatePage)
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

    private fun load(input: String) {
        webView.loadUrl(normalizeUrl(input))
    }

    // ---------------------------------------------------------------- link e idiomas

    /** Crea el traductor con los idiomas guardados. */
    private fun loadLanguages() {
        translator?.close()
        val source = Languages.source(this)
        val target = Languages.target(this)
        translator = Languages.translator(source, target)
        modelReady = false
        cache.clear()
        statusText.text = if (translator == null) {
            "El idioma de origen y el de destino son iguales: no se traducirá."
        } else {
            "${source.name} → ${target.name}"
        }
        toggleButton.visibility = if (translator == null) View.INVISIBLE else View.VISIBLE
    }

    private fun showLinkDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_link, null)
        val input = view.findViewById<EditText>(R.id.urlInput)
        val sourceSpinner = view.findViewById<Spinner>(R.id.sourceSpinner)
        val targetSpinner = view.findViewById<Spinner>(R.id.targetSpinner)
        input.setText(webView.url.orEmpty())
        fillSpinner(sourceSpinner, Languages.sources, Languages.source(this))
        fillSpinner(targetSpinner, Languages.targets, Languages.target(this))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.web_link_title)
            .setView(view)
            .setPositiveButton(R.string.web_open, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val open = {
            val url = input.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Escribe o pega un enlace", Toast.LENGTH_SHORT).show()
            } else {
                val source = Languages.sources[sourceSpinner.selectedItemPosition]
                val target = Languages.targets[targetSpinner.selectedItemPosition]
                if (source != Languages.source(this) || target != Languages.target(this)) {
                    Languages.save(this, source, target)
                    loadLanguages()
                }
                dialog.dismiss()
                load(url)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) open()
            actionId == EditorInfo.IME_ACTION_GO
        }
        dialog.setOnShowListener {
            // Así el botón no cierra el diálogo si el enlace está vacío.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { open() }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun fillSpinner(spinner: Spinner, items: List<Language>, selected: Language) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        spinner.setSelection(items.indexOf(selected))
    }

    // ---------------------------------------------------------------- páginas

    private fun schedulePageUpdate() {
        mainHandler.removeCallbacks(updatePage)
        mainHandler.postDelayed(updatePage, 150)
    }

    /** Cada «página» es una pantalla del marco; se calcula desde la propia web. */
    private fun refreshPageNumber() {
        webView.evaluateJavascript(
            """(function(){var e=document.scrollingElement||document.documentElement;
            var h=window.innerHeight||1,y=e.scrollTop,H=Math.max(e.scrollHeight,h);
            var t=Math.max(1,Math.ceil((H-2)/h)),p=Math.min(t,Math.floor((y+2)/h)+1);
            if(y+h>=H-2)p=t;return [p,t];})()"""
        ) { result ->
            val values = runCatching { JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            showPage(values.getInt(0), values.getInt(1))
        }
    }

    private fun showPage(page: Int, total: Int) {
        currentPage = page
        totalPages = total
        pageText.text = String.format("Pág %02d/%02d", page, total)
    }

    private fun goToPage(page: Int) {
        if (webView.url == null || page < 1 || page > totalPages) return
        webView.evaluateJavascript(
            "window.scrollTo({top: ${page - 1} * window.innerHeight, behavior: 'smooth'})", null,
        )
        showPage(page, totalPages)
        mainHandler.removeCallbacks(updatePage)
        mainHandler.postDelayed(updatePage, 600)
    }

    // ---------------------------------------------------------------- descargar

    /** Guarda como .txt el texto que se ve ahora (traducido u original). */
    private fun download() {
        if (webView.url == null) {
            Toast.makeText(this, "Primero abre una página con «Link»", Toast.LENGTH_SHORT).show()
            return
        }
        if (pending > 0) Toast.makeText(this, "Aún se está traduciendo: se guardará lo que ya esté listo", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript("[document.title, document.body ? document.body.innerText : '']") { result ->
            val values = runCatching { JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            val title = values.optString(0).ifBlank { "pagina" }
            downloadText = title + "\n" + webView.url + "\n\n" + values.optString(1).trim() + "\n"
            val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "pagina" }
            saveLauncher.launch("$name.txt")
        }
    }

    private fun save(uri: Uri) {
        val text = downloadText
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                        ?: throw java.io.IOException("no se pudo abrir el archivo")
                }
                Toast.makeText(this@WebTranslateActivity, "Traducción guardada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@WebTranslateActivity, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
            if (page == pageId) {
                webView.evaluateJavascript("window.__tr && window.__tr.apply($results)", null)
                // Al traducir cambia la altura del texto, y con ella el número de páginas.
                schedulePageUpdate()
            }
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
