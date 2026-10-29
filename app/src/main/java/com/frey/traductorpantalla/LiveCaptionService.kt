package com.frey.traductorpantalla

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Subtítulos de directos: escucha el audio de las apps (o el micrófono),
 * lo pasa a texto en el móvil (SenseVoice o Vosk, ver [SpeechEngine]), lo
 * traduce con ML Kit y lo muestra en un recuadro negro flotante.
 */
class LiveCaptionService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "directos"
        private const val NOTIFICATION_ID = 2
        private const val SAMPLE_RATE = SpeechEngine.SAMPLE_RATE
        private const val PARTIAL_TRANSLATE_MS = 700L
        private const val SILENCE_HINT_MS = 8000L

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var engine: SpeechEngine? = null
    private var autoDetect = false
    /** Un traductor por cada idioma que se puede oír (en automático, varios). */
    private val translators = HashMap<String, Translator?>()
    /** Último idioma detectado, para indicarlo en el recuadro. */
    private var shownLanguage = ""

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile
    private var listening = false

    private var box: LinearLayout? = null
    private var linesView: TextView? = null
    /** En modo automático, el idioma detectado («Japonés → Español»). */
    private var languageView: TextView? = null
    private var partialView: TextView? = null
    /** Últimas frases ya traducidas. */
    private val lines = ArrayDeque<String>()
    private var lastPartialTranslation = 0L
    private var translatingPartial = false
    /** Cuenta las frases terminadas: una traducción parcial que llega tarde no pisa la final. */
    private var phrase = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val useMic = LiveCaptions.useMicrophone(this) || Build.VERSION.SDK_INT < 29
        val type = when {
            Build.VERSION.SDK_INT < 30 -> 0
            useMic -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!useMic) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
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
        }
        isRunning = true
        windowManager = getSystemService(WindowManager::class.java)
        autoDetect = LiveCaptions.autoDetect(this)
        addCaptionBox()
        showPartial("Cargando el reconocimiento de voz…")

        scope.launch {
            try {
                val target = Languages.target(this@LiveCaptionService)
                for (language in LiveCaptions.possibleSources(this@LiveCaptionService)) {
                    val translator = Languages.translator(language, target)
                    translator?.let { Languages.downloadModel(it) }
                    translators[language.code] = translator
                }
                val source = LiveCaptions.source(this@LiveCaptionService)
                val callback: SpeechCallback = { text, detected, isFinal ->
                    // Con un idioma elegido, ese es el idioma aunque el motor diga otra cosa.
                    val code = if (autoDetect) detected else source.code
                    post { if (isFinal) onFinal(text, code) else onPartial(text, code) }
                }
                engine = withContext(Dispatchers.IO) {
                    when {
                        autoDetect -> SenseVoiceEngine(this@LiveCaptionService, "", callback)
                        LiveCaptions.usesSenseVoice(this@LiveCaptionService) ->
                            SenseVoiceEngine(this@LiveCaptionService, source.code, callback)
                        else -> VoskEngine(LiveCaptions.modelDir(this@LiveCaptionService, source), source, callback)
                    }
                }
                startListening(useMic)
                showPartial(if (autoDetect) "Escuchando… detectando el idioma" else "Escuchando…")
            } catch (e: Exception) {
                Toast.makeText(this@LiveCaptionService, "No se pudo iniciar: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- audio y voz

    @SuppressLint("MissingPermission") // El permiso de grabar audio se pide antes de iniciar.
    private fun startListening(useMic: Boolean) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            // Margen de 4 s por si el reconocimiento se retrasa un momento.
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2 * 4))
        val mp = projection
        if (!useMic && mp != null && Build.VERSION.SDK_INT >= 29) {
            // Captura el sonido que reproducen las demás apps (vídeos, directos, juegos).
            builder.setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            )
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("no se pudo abrir el audio")
        val engine = engine ?: throw IllegalStateException("sin reconocimiento de voz")
        audioRecord = record
        listening = true
        record.startRecording()

        audioThread = Thread({
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var lastSound = System.currentTimeMillis()
            var hinted = false
            try {
                while (listening) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    if ((0 until n).any { abs(buffer[it].toInt()) > 60 }) {
                        lastSound = System.currentTimeMillis()
                        hinted = false
                    } else if (!hinted && System.currentTimeMillis() - lastSound > SILENCE_HINT_MS) {
                        hinted = true
                        post { if (lines.isEmpty()) showPartial(silenceHint(useMic)) }
                    }
                    engine.accept(buffer, n)
                }
            } finally {
                engine.close()
            }
        }, "subtitulos").apply { start() }
    }

    private fun silenceHint(useMic: Boolean) = if (useMic) {
        "No se oye nada por el micrófono…"
    } else {
        "Sin sonido. Si el directo se oye, esa app no deja capturar su audio: prueba la opción «Micrófono»."
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { if (isRunning) block() }
    }

    private fun translate(text: String, code: String) = scope.async {
        val translator = translators[code] ?: return@async text
        runCatching { translator.translate(text).await() }.getOrDefault(text)
    }

    /** En automático, indica arriba del recuadro qué idioma se está oyendo. */
    private fun showLanguage(code: String) {
        if (!autoDetect || code == shownLanguage) return
        shownLanguage = code
        val name = Languages.sources.firstOrNull { it.code == code }?.name ?: code
        languageView?.text = "$name → ${Languages.target(this).name}"
        languageView?.visibility = android.view.View.VISIBLE
    }

    private fun onFinal(text: String, code: String) {
        if (text.isEmpty()) return
        // En automático, un idioma que no se puede traducir (p. ej. ruido) no se muestra.
        if (code !in translators) return
        phrase++
        scope.launch {
            val translated = translate(text, code).await()
            showLanguage(code)
            lines.addLast(translated)
            while (lines.size > 2) lines.removeFirst()
            linesView?.text = lines.joinToString("\n")
            showPartial("")
        }
    }

    /** Traduce lo que se está diciendo, como mucho cada [PARTIAL_TRANSLATE_MS]. */
    private fun onPartial(text: String, code: String) {
        if (text.isEmpty() || translatingPartial || code !in translators) return
        val now = System.currentTimeMillis()
        if (now - lastPartialTranslation < PARTIAL_TRANSLATE_MS) return
        lastPartialTranslation = now
        translatingPartial = true
        val current = phrase
        scope.launch {
            try {
                val translated = translate(text, code).await()
                if (current != phrase) return@launch
                showLanguage(code)
                showPartial("$translated…")
            } finally {
                translatingPartial = false
            }
        }
    }

    // ---------------------------------------------------------------- recuadro

    private fun showPartial(text: String) {
        partialView?.text = text
        partialView?.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        linesView?.visibility = if (lines.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCaptionBox() {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.argb(225, 0, 0, 0))
            }
        }
        val linesText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(0f, 1.1f)
        }
        val partialText = TextView(this).apply {
            setTextColor(Color.rgb(190, 190, 190))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        val languageText = TextView(this).apply {
            setTextColor(Color.rgb(150, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = android.view.View.GONE
        }
        layout.addView(languageText)
        layout.addView(linesText)
        layout.addView(partialText)

        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            metrics.widthPixels - (24 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (metrics.heightPixels * 0.62f).toInt()
        }

        // Se puede arrastrar a cualquier parte de la pantalla.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(layout, params)
                }
            }
            true
        }
        windowManager.addView(layout, params)
        box = layout
        linesView = linesText
        partialView = partialText
        languageView = languageText
    }

    // ---------------------------------------------------------------- notificación

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Subtítulos de directos", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveCaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LiveActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Subtítulos de directos activos")
            .setContentText("Traduciendo lo que se dice · arrastra el recuadro para moverlo")
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        listening = false
        scope.cancel()
        box?.let { runCatching { windowManager.removeView(it) } }
        // Esperar a que el hilo suelte el reconocedor antes de liberar el modelo.
        audioThread?.join(1000)
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        // Si el hilo de audio no llegó a arrancar, el motor se cierra aquí.
        if (audioThread == null) engine?.close()
        translators.values.forEach { it?.close() }
        projection?.stop()
        super.onDestroy()
    }
}
