package com.frey.traductorpantalla

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Lector que traduce páginas web en el propio móvil: el script
 * web_translator.js extrae los textos, aquí se traducen (ver TextTranslator) y se
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

    private val saveTextLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { saveText(it) } }

    /** Capítulo que se va a descargar mientras el usuario elige dónde guardarlo. */
    private class PendingExport(
        val format: MangaFormat,
        val translateImages: Boolean,
        val title: String,
        val urls: List<String>,
        val referer: String,
    )
    private var pendingExport: PendingExport? = null
    private val exportLaunchers = MangaFormat.entries.associateWith { format ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            uri?.let { exportManga(it) }
        }
    }

    private lateinit var script: String
    private var translator: TextTranslator? = null

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
        translator = if (source.code == target.code) null else TextTranslator(this, source, target)
        statusText.text = when {
            translator == null -> "El idioma de origen y el de destino son iguales: no se traducirá."
            source == Languages.AUTO -> "Idioma detectado automáticamente → ${target.name}"
            else -> "${source.name} → ${target.name}"
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

    /** Pregunta el formato: imágenes del manga (ZIP, PDF, EPUB) o solo texto. */
    private fun download() {
        if (webView.url == null) {
            Toast.makeText(this, "Primero abre una página con «Link»", Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val group = view.findViewById<RadioGroup>(R.id.formatGroup)
        val translateImages = view.findViewById<CheckBox>(R.id.translateImages)
        group.setOnCheckedChangeListener { _, id ->
            translateImages.visibility = if (id == R.id.formatTxt) View.GONE else View.VISIBLE
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setView(view)
            .setPositiveButton(R.string.web_download) { _, _ ->
                when (group.checkedRadioButtonId) {
                    R.id.formatTxt -> downloadText()
                    R.id.formatPdf -> downloadManga(MangaFormat.PDF, translateImages.isChecked)
                    R.id.formatEpub -> downloadManga(MangaFormat.EPUB, translateImages.isChecked)
                    else -> downloadManga(MangaFormat.ZIP, translateImages.isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fileName(title: String) =
        title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "pagina" }

    /** Guarda como .txt el texto que se ve ahora (traducido u original). */
    private fun downloadText() {
        if (pending > 0) Toast.makeText(this, "Aún se está traduciendo: se guardará lo que ya esté listo", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript("[document.title, document.body ? document.body.innerText : '']") { result ->
            val values = runCatching { JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            val title = values.optString(0).ifBlank { "pagina" }
            downloadText = title + "\n" + webView.url + "\n\n" + values.optString(1).trim() + "\n"
            saveTextLauncher.launch("${fileName(title)}.txt")
        }
    }

    private fun saveText(uri: Uri) {
        val text = downloadText
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                        ?: throw IOException("no se pudo abrir el archivo")
                }
                Toast.makeText(this@WebTranslateActivity, "Traducción guardada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@WebTranslateActivity, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Busca las imágenes grandes de la página (las del capítulo), en orden. */
    private fun downloadManga(format: MangaFormat, translateImages: Boolean) {
        webView.evaluateJavascript(
            """(function(){var out=[],seen={};var imgs=document.images;
            for(var i=0;i<imgs.length;i++){var im=imgs[i];
              var lazy=im.getAttribute('data-src')||im.getAttribute('data-lazy-src')||im.getAttribute('data-original')||im.getAttribute('data-url');
              var src=im.currentSrc||im.src;
              if(lazy&&(!src||/^data:image\/(gif|svg)/.test(src)||!im.naturalWidth))src=lazy;
              if(!src||src.indexOf('blob:')==0)continue;
              try{src=new URL(src.trim(),location.href).href}catch(e){continue}
              var w=im.naturalWidth,h=im.naturalHeight;
              if(w&&h&&(w<200||h<200))continue;
              if(seen[src])continue;seen[src]=1;out.push(src);}
            return [document.title,out];})()"""
        ) { result ->
            val values = runCatching { JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            val urls = values.getJSONArray(1).let { list -> (0 until list.length()).map { list.getString(it) } }
            if (urls.isEmpty()) {
                Toast.makeText(this, "No se encontraron imágenes en esta página", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            val title = values.optString(0).ifBlank { "manga" }
            pendingExport = PendingExport(format, translateImages, title, urls, webView.url.orEmpty())
            exportLaunchers.getValue(format).launch("${fileName(title)}.${format.extension}")
        }
    }

    private fun exportManga(uri: Uri) {
        val export = pendingExport ?: return
        pendingExport = null
        val message = TextView(this).apply {
            setPadding(64, 32, 64, 0)
            text = "Buscando imágenes…"
        }
        val exporter = MangaExporter(this, Languages.source(this), Languages.target(this), export.translateImages)
        val userAgent = webView.settings.userAgentString
        var job: Job? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setView(message)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        job = lifecycleScope.launch {
            var ok = false
            try {
                val pages = exporter.preparePages(export.urls, export.referer, userAgent) { message.text = it }
                message.text = "Guardando ${pages.size} páginas…"
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { exporter.write(export.format, pages, export.title, it) }
                        ?: throw IOException("no se pudo abrir el archivo")
                }
                ok = true
                Toast.makeText(this@WebTranslateActivity, "Capítulo guardado (${pages.size} páginas)", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                Toast.makeText(this@WebTranslateActivity, "Descarga cancelada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@WebTranslateActivity, "No se pudo descargar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                exporter.close()
                if (!ok) withContext(NonCancellable + Dispatchers.IO) {
                    // No dejar un archivo vacío o a medias.
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                }
                dialog.dismiss()
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

    private suspend fun translateBatch(page: Int, batch: JSONArray) {
        try {
            val current = translator ?: return
            if (page != pageId) return
            val items = (0 until batch.length()).map { batch.getJSONObject(it) }
            // Todo el lote de una vez: en línea son pocas peticiones, y en
            // automático el idioma se averigua con más texto.
            val translated = current.translateAll(items.map { it.getString("text") })
            if (page != pageId) return
            val results = JSONArray()
            items.forEachIndexed { i, item -> results.put(JSONObject().put("id", item.getInt("id")).put("text", translated[i])) }
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
