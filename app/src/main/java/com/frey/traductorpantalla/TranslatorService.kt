package com.frey.traductorpantalla

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servicio en primer plano que mantiene la captura de pantalla, la burbuja
 * flotante y la capa con las traducciones.
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_UPDATE_LANGUAGES = "update_languages"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "traductor"
        private const val NOTIFICATION_ID = 1
        private const val LIVE_INTERVAL_MS = 1500L

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val captureThread = HandlerThread("captura").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val imageLock = Any()
    private var latestImage: Image? = null
    private val frameCount = AtomicInteger()
    private var screenWidth = 0
    private var screenHeight = 0

    private var overlay: TranslationOverlayView? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private lateinit var source: Language
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var modelReady = false
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    private var busy = false
    private var liveJob: Job? = null
    private var lastSignature = ""
    /** Número de fotogramas cuando la pantalla quedó quieta tras mostrar la traducción. */
    private var settledFrame = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_LANGUAGES -> {
                if (projection != null) loadLanguages() else stopSelf()
                return START_NOT_STICKY
            }
        }

        // Android exige estar en primer plano antes de obtener la MediaProjection.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)
        projection = mp
        isRunning = true

        windowManager = getSystemService(WindowManager::class.java)
        loadLanguages()
        startCapture()
        addOverlay()
        addBubble()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- idiomas

    private fun loadLanguages() {
        recognizer?.close()
        translator?.close()
        source = Languages.source(this)
        recognizer = Languages.recognizer(source)
        translator = Languages.translator(source, Languages.target(this))
        modelReady = false
        cache.clear()
        lastSignature = ""
        overlay?.blocks = emptyList()
    }

    private suspend fun ensureModel(translator: Translator) {
        if (modelReady) return
        Toast.makeText(this, "Preparando idioma…", Toast.LENGTH_SHORT).show()
        Languages.downloadModel(translator)
        modelReady = true
    }

    // ---------------------------------------------------------------- captura

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }

    private fun newImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = image
            }
            frameCount.incrementAndGet()
        }, captureHandler)
        return reader
    }

    private fun startCapture() {
        val metrics = realMetrics()
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val reader = newImageReader(screenWidth, screenHeight)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "traductor", screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        val metrics = realMetrics()
        if (metrics.widthPixels == screenWidth && metrics.heightPixels == screenHeight) return
        // Pantalla girada: redimensionamos la captura y quitamos traducciones viejas.
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val oldReader = imageReader
        val reader = newImageReader(screenWidth, screenHeight)
        imageReader = reader
        display.resize(screenWidth, screenHeight, metrics.densityDpi)
        display.surface = reader.surface
        captureHandler.post {
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            oldReader?.close()
        }
        overlay?.blocks = emptyList()
        lastSignature = ""
        bubbleParams?.let { params ->
            params.x = params.x.coerceIn(0, (screenWidth - (bubble?.width ?: 0)).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenHeight - (bubble?.height ?: 0)).coerceAtLeast(0))
            bubble?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun latestBitmap(): Bitmap? = synchronized(imageLock) {
        val image = latestImage ?: return null
        val plane = image.planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
            padded.recycle()
        }
    }

    /**
     * Oculta la burbuja y las traducciones un instante para que la captura
     * solo contenga lo que hay realmente en pantalla.
     */
    private suspend fun captureCleanScreen(): Bitmap? {
        overlay?.visibility = View.INVISIBLE
        bubble?.visibility = View.INVISIBLE
        delay(220)
        val bitmap = withContext(Dispatchers.Default) { latestBitmap() }
        bubble?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
        return bitmap
    }

    // ---------------------------------------------------------------- traducción

    private fun translateScreen(fromLive: Boolean) {
        if (busy) return
        busy = true
        setBubbleBusy(true)
        scope.launch {
            try {
                val bitmap = captureCleanScreen() ?: return@launch
                val currentRecognizer = recognizer ?: return@launch
                val currentTranslator = translator
                val result = currentRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                bitmap.recycle()

                val found = result.textBlocks.mapNotNull { block ->
                    val box = block.boundingBox ?: return@mapNotNull null
                    val text = block.lines.joinToString(source.lineSeparator) { it.text.trim() }
                    if (text.count { it.isLetter() } < 2) null else box to text
                }
                val signature = found.joinToString("\u0000") { it.second }
                if (fromLive && signature == lastSignature) return@launch
                lastSignature = signature

                if (found.isEmpty()) {
                    overlay?.blocks = emptyList()
                    if (!fromLive) Toast.makeText(this@TranslatorService, "No se encontró texto", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (currentTranslator != null) ensureModel(currentTranslator)
                overlay?.blocks = found.map { (box, text) ->
                    val translated = cache[text]
                        ?: (currentTranslator?.translate(text)?.await() ?: text).also { cache[text] = it }
                    TranslatedBlock(box, translated)
                }
            } catch (e: Exception) {
                lastSignature = ""
                Toast.makeText(this@TranslatorService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
                setBubbleBusy(false)
                markSettledLater()
            }
        }
    }

    /** Recuerda el contador de fotogramas cuando la pantalla deja de cambiar por nuestra culpa. */
    private fun markSettledLater() {
        scope.launch {
            delay(500)
            settledFrame = frameCount.get()
        }
    }

    private fun toggleLive() {
        if (liveJob != null) {
            liveJob?.cancel()
            liveJob = null
            Toast.makeText(this, "Tiempo real desactivado", Toast.LENGTH_SHORT).show()
        } else {
            lastSignature = ""
            liveJob = scope.launch {
                while (isActive) {
                    // Si no ha llegado ningún fotograma nuevo, la pantalla no cambió.
                    if (!busy && frameCount.get() != settledFrame) translateScreen(fromLive = true)
                    delay(LIVE_INTERVAL_MS)
                }
            }
            Toast.makeText(this, "Tiempo real activado", Toast.LENGTH_SHORT).show()
        }
        updateBubbleColor()
    }

    // ---------------------------------------------------------------- ventanas

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addOverlay() {
        val view = TranslationOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Desde Android 12 los toques solo atraviesan capas de otras apps
            // si su opacidad es como mucho 0.8.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        overlay = view
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun addBubble() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val view = TextView(this).apply {
            text = "文A"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 6 * density
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - (8 * density).toInt()
            y = screenHeight / 3
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            toggleLive()
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    mainHandler.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!dragging && !longPressed) onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPress)
            }
            true
        }
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
        updateBubbleColor()
    }

    private fun onBubbleTap() {
        val current = overlay ?: return
        if (liveJob == null && current.blocks.isNotEmpty()) {
            current.blocks = emptyList()
            lastSignature = ""
        } else {
            translateScreen(fromLive = false)
        }
    }

    private fun updateBubbleColor() {
        val color = if (liveJob != null) Color.rgb(46, 125, 50) else Color.rgb(21, 101, 192)
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
    }

    private fun setBubbleBusy(isBusy: Boolean) {
        bubble?.alpha = if (isBusy) 0.6f else 1f
    }

    // ---------------------------------------------------------------- notificación

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Traductor activo", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Traductor de pantalla activo")
            .setContentText("Toca la burbuja para traducir · mantenla pulsada para tiempo real")
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        overlay?.let { runCatching { windowManager.removeView(it) } }
        bubble?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        projection?.stop()
        projection = null
        captureHandler.post {
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            imageReader?.close()
            captureThread.quitSafely()
        }
        recognizer?.close()
        translator?.close()
        super.onDestroy()
    }
}
