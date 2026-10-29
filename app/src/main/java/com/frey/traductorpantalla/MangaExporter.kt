package com.frey.traductorpantalla

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.webkit.CookieManager
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

enum class MangaFormat(val extension: String, val mimeType: String) {
    ZIP("zip", "application/zip"),
    PDF("pdf", "application/pdf"),
    EPUB("epub", "application/epub+zip"),
}

data class MangaPage(val file: File, val width: Int, val height: Int)

/**
 * Descarga las imágenes de un capítulo de manga, traduce el texto de cada
 * una (OCR + ML Kit, dibujando la traducción encima del bocadillo) y las
 * empaqueta como ZIP, PDF o EPUB.
 */
class MangaExporter(
    private val context: Context,
    private val source: Language,
    private val target: Language,
    private val translateImages: Boolean,
) {
    companion object {
        /** Las tiras largas (webtoons) se cortan en páginas de como mucho esta altura. */
        private const val MAX_PAGE_HEIGHT = 3000
        private const val MAX_PAGE_WIDTH = 1600
        private const val MIN_IMAGE_SIZE = 200
    }

    private val dir = File(context.cacheDir, "manga").apply {
        deleteRecursively()
        mkdirs()
    }
    private val recognizer: TextRecognizer? = if (translateImages) Languages.recognizer(source) else null
    private val translator: Translator? = if (translateImages) Languages.translator(source, target) else null
    private val cache = HashMap<String, String>()

    fun close() {
        recognizer?.close()
        translator?.close()
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- páginas

    /** Descarga y traduce cada imagen; [onProgress] recibe un texto para mostrar. */
    suspend fun preparePages(
        urls: List<String>,
        referer: String,
        userAgent: String,
        onProgress: (String) -> Unit,
    ): List<MangaPage> {
        translator?.let {
            withContext(Dispatchers.Main) { onProgress("Preparando el idioma…") }
            Languages.downloadModel(it)
        }
        val pages = mutableListOf<MangaPage>()
        for ((index, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            val step = "Imagen ${index + 1} de ${urls.size}"
            withContext(Dispatchers.Main) { onProgress("$step: descargando…") }
            val bytes = runCatching { withContext(Dispatchers.IO) { fetch(url, referer, userAgent) } }.getOrNull() ?: continue
            pages += splitIntoPages(bytes) { part, parts ->
                val detail = if (parts > 1) " (parte $part de $parts)" else ""
                withContext(Dispatchers.Main) {
                    onProgress(if (translateImages) "$step$detail: traduciendo…" else "$step$detail")
                }
            }
        }
        if (pages.isEmpty()) throw IOException("no se pudo descargar ninguna imagen")
        return pages
    }

    private fun fetch(url: String, referer: String, userAgent: String): ByteArray {
        if (url.startsWith("data:")) {
            return Base64.decode(url.substringAfter(","), Base64.DEFAULT)
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        // Muchas webs de manga solo sirven las imágenes a quien viene de su página.
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decodifica la imagen por trozos (así una tira de 20 000 px no agota la
     * memoria), traduce cada trozo y lo guarda como una página JPEG.
     */
    private suspend fun splitIntoPages(
        bytes: ByteArray,
        onPart: suspend (part: Int, parts: Int) -> Unit,
    ): List<MangaPage> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        // Iconos, logos y anuncios pequeños no son páginas.
        if (width < MIN_IMAGE_SIZE || height < MIN_IMAGE_SIZE) return emptyList()
        var sample = 1
        while (width / sample > MAX_PAGE_WIDTH) sample *= 2

        @Suppress("DEPRECATION")
        val decoder = runCatching { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }.getOrNull()
        val chunk = MAX_PAGE_HEIGHT * sample
        val parts = if (decoder == null) 1 else (height + chunk - 1) / chunk
        val pages = mutableListOf<MangaPage>()
        try {
            for (part in 0 until parts) {
                coroutineContext.ensureActive()
                onPart(part + 1, parts)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = withContext(Dispatchers.Default) {
                    if (decoder != null) {
                        decoder.decodeRegion(Rect(0, part * chunk, width, minOf(height, (part + 1) * chunk)), options)
                    } else {
                        // Formatos sin decodificación por trozos (GIF…).
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                } ?: continue
                if (translateImages) translateBitmap(bitmap)
                val file = File(dir, "%04d.jpg".format(dir.list()?.size ?: 0))
                withContext(Dispatchers.IO) {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
                pages += MangaPage(file, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        } finally {
            decoder?.recycle()
        }
        return pages
    }

    // ---------------------------------------------------------------- traducción

    private suspend fun translateBitmap(bitmap: Bitmap) {
        val recognizer = recognizer ?: return
        val translator = translator ?: return
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val canvas = Canvas(bitmap)
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            val text = block.lines.joinToString(source.lineSeparator) { it.text.trim() }
            if (text.none { it.isLetter() }) continue
            val translated = cache[text] ?: translator.translate(text).await().also { cache[text] = it }
            drawTranslation(canvas, bitmap.width, box, translated)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Tapa el texto original con un recuadro blanco, como un bocadillo, y escribe la traducción. */
    private fun drawTranslation(canvas: Canvas, imageWidth: Int, box: Rect, text: String) {
        val pad = imageWidth * 0.006f
        // El texto japonés suele ir en columnas estrechas: se ensancha el
        // recuadro para que la traducción horizontal quepa con letra legible.
        val minWidth = minOf(box.height() * 0.6f, imageWidth * 0.35f)
        val extra = ((minWidth - box.width()) / 2).coerceAtLeast(0f)
        val left = (box.left - extra - pad).coerceAtLeast(0f)
        val right = (box.right + extra + pad).coerceAtMost(imageWidth.toFloat())
        val width = (right - left - 2 * pad).toInt().coerceAtLeast(1)
        val layout = fitText(text, width, box.height(), imageWidth)
        val height = maxOf(box.height().toFloat(), layout.height.toFloat()) + 2 * pad
        val rect = RectF(left, box.top - pad, right, box.top - pad + height)
        canvas.drawRoundRect(rect, pad * 2, pad * 2, fill)
        canvas.save()
        canvas.translate(rect.left + pad, rect.top + pad + ((height - 2 * pad - layout.height) / 2).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun fitText(text: String, width: Int, height: Int, imageWidth: Int): StaticLayout {
        val minSize = maxOf(10f, imageWidth * 0.022f)
        var size = minOf(height.toFloat(), imageWidth * 0.05f).coerceAtLeast(minSize)
        while (true) {
            textPaint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            if (layout.height <= height || size <= minSize) return layout
            size = maxOf(minSize, size * 0.9f)
        }
    }

    // ---------------------------------------------------------------- formatos

    suspend fun write(format: MangaFormat, pages: List<MangaPage>, title: String, out: OutputStream) =
        withContext(Dispatchers.IO) {
            when (format) {
                MangaFormat.ZIP -> writeZip(pages, out)
                MangaFormat.PDF -> writePdf(pages, out)
                MangaFormat.EPUB -> writeEpub(pages, title, out)
            }
        }

    private fun pageName(index: Int) = "%03d".format(index + 1)

    private fun writeZip(pages: List<MangaPage>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            pages.forEachIndexed { i, page ->
                zip.putNextEntry(ZipEntry("${pageName(i)}.jpg"))
                page.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writePdf(pages: List<MangaPage>, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, page ->
                val bitmap = BitmapFactory.decodeFile(page.file.path) ?: return@forEachIndexed
                val pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create())
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun writeEpub(pages: List<MangaPage>, title: String, out: OutputStream) {
        val safeTitle = title.escapeXml()
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        ZipOutputStream(out).use { zip ->
            // El primer archivo de un EPUB debe ser «mimetype», sin comprimir.
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()

            fun text(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }

            text(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""",
            )

            val manifest = StringBuilder()
            val spine = StringBuilder()
            pages.forEachIndexed { i, page ->
                val name = pageName(i)
                zip.putNextEntry(ZipEntry("OEBPS/images/$name.jpg"))
                page.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                text(
                    "OEBPS/p$name.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="UTF-8"/>
<title>$name</title>
<meta name="viewport" content="width=${page.width}, height=${page.height}"/>
<style>html, body { margin: 0; padding: 0; } img { display: block; width: 100%; height: 100%; }</style>
</head>
<body><img src="images/$name.jpg" alt=""/></body>
</html>
""",
                )
                val cover = if (i == 0) " properties=\"cover-image\"" else ""
                manifest.append("    <item id=\"img$name\" href=\"images/$name.jpg\" media-type=\"image/jpeg\"$cover/>\n")
                manifest.append("    <item id=\"p$name\" href=\"p$name.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("    <itemref idref=\"p$name\"/>\n")
            }

            text(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="UTF-8"/><title>$safeTitle</title></head>
<body><nav epub:type="toc"><ol><li><a href="p001.xhtml">$safeTitle</a></li></ol></nav></body>
</html>
""",
            )
            text(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid"
         prefix="rendition: http://www.idpf.org/vocab/rendition/#">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
    <dc:title>$safeTitle</dc:title>
    <dc:language>${target.code}</dc:language>
    <meta property="dcterms:modified">$modified</meta>
    <meta property="rendition:layout">pre-paginated</meta>
    <meta property="rendition:spread">none</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>
""",
            )
        }
    }

    private fun String.escapeXml() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
