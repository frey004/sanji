package com.frey.traductorpantalla

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Texto reconocido: [languageCode] es el idioma detectado; [isFinal], si la frase terminó. */
typealias SpeechCallback = (text: String, languageCode: String, isFinal: Boolean) -> Unit

/** Convierte el audio (16 kHz, mono) en texto. Todo se llama desde el hilo de audio. */
interface SpeechEngine {
    fun accept(samples: ShortArray, n: Int)
    fun close()

    /** Última vez que el motor dio señales de vida (para detectar si se ha colgado). */
    val lastActivity: Long

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

/**
 * SenseVoice (sherpa-onnx): japonés, chino, coreano e inglés, con detección
 * del idioma. No es de flujo continuo, así que un detector de voz (Silero)
 * separa las frases y, mientras alguien habla, se vuelve a reconocer lo dicho
 * para ir mostrando el texto.
 *
 * El reconocimiento va en su propio hilo: el de audio solo encola. Si el móvil
 * no da abasto se saltan los textos provisionales y, si aun así se acumula
 * mucho retraso, se descarta lo atrasado para volver al presente.
 */
class SenseVoiceEngine(
    context: Context,
    /** "" para detectar el idioma; si no, "ja", "zh", "ko" o "en". */
    language: String,
    private val callback: SpeechCallback,
) : SpeechEngine {

    companion object {
        private const val WINDOW = 512
        /** Al empezar a hablar se incluye también lo de justo antes (0,4 s). */
        private const val LEAD_IN = 6400
        private const val PARTIAL_EVERY_MS = 600L
        /** Con más audio pendiente que esto (en trozos de 0,1 s) no se hacen parciales. */
        private const val MAX_BACKLOG_FOR_PARTIAL = 3
        /** Con más de 8 s de retraso se descarta lo pendiente. */
        private const val MAX_BACKLOG = 80
        /** A veces separa con espacios las palabras en japonés o chino, que no los llevan. */
        private val cjkSpaces = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー])")
    }

    private val vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                // Pausas más cortas que esto no cortan la frase: así no se
                // traducen trozos sueltos cuando alguien habla deprisa.
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                // Las frases largas se cortan para que el subtítulo no tarde demasiado.
                maxSpeechDuration = 6f,
            ),
            sampleRate = SpeechEngine.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(LiveCaptions.senseVoiceDir(context), "model.int8.onnx").path,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = File(LiveCaptions.senseVoiceDir(context), "tokens.txt").path,
                // Más núcleos, menos retraso: hasta 4 en móviles de 8 núcleos.
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
            ),
        ),
    )

    private var buffer = FloatArray(SpeechEngine.SAMPLE_RATE * 20)
    private var size = 0
    /** Hasta dónde ha analizado el detector de voz. */
    private var offset = 0
    /** Dónde empezó la frase actual, o -1 si nadie habla. */
    private var speechStart = -1
    private var lastDecode = 0L
    /** Cuánto tardó el último reconocimiento provisional. */
    private var lastDecodeCost = 0L

    private val queue = LinkedBlockingQueue<FloatArray>()
    @Volatile
    private var running = true
    @Volatile
    override var lastActivity = System.currentTimeMillis()
        private set
    private val worker = Thread({ work() }, "sensevoice").apply { start() }

    override fun accept(samples: ShortArray, n: Int) {
        queue.offer(FloatArray(n) { samples[it] / 32768f })
    }

    private fun work() {
        try {
            while (running) {
                lastActivity = System.currentTimeMillis()
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (queue.size > MAX_BACKLOG) {
                    queue.clear()
                    reset()
                    continue
                }
                try {
                    process(chunk)
                } catch (e: Exception) {
                    // Un fallo puntual no puede detener los subtítulos.
                    reset()
                }
            }
        } finally {
            recognizer.release()
            vad.release()
        }
    }

    private fun reset() {
        vad.reset()
        size = 0
        offset = 0
        speechStart = -1
    }

    private fun process(chunk: FloatArray) {
        val n = chunk.size
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        chunk.copyInto(buffer, size)
        size += n

        while (offset + WINDOW <= size) {
            vad.acceptWaveform(buffer.copyOfRange(offset, offset + WINDOW))
            offset += WINDOW
            if (speechStart < 0 && vad.isSpeechDetected()) {
                speechStart = maxOf(0, offset - LEAD_IN)
                lastDecode = System.currentTimeMillis()
            }
        }

        var finished = false
        while (!vad.empty()) {
            decode(vad.front().samples)?.let { (text, lang) -> callback(text, lang, true) }
            vad.pop()
            finished = true
        }
        if (finished) {
            speechStart = -1
            keepFrom(offset)
        } else if (speechStart < 0 && offset > SpeechEngine.SAMPLE_RATE * 2) {
            // En silencio no hace falta guardar más que lo último.
            keepFrom(offset - LEAD_IN)
        }

        // Texto provisional mientras se habla, solo si el reconocimiento va al día.
        val now = System.currentTimeMillis()
        if (speechStart >= 0 && queue.size <= MAX_BACKLOG_FOR_PARTIAL &&
            now - lastDecode > maxOf(PARTIAL_EVERY_MS, lastDecodeCost * 2)
        ) {
            decode(buffer.copyOfRange(speechStart, offset))?.let { (text, lang) -> callback(text, lang, false) }
            lastDecode = System.currentTimeMillis()
            lastDecodeCost = lastDecode - now
        }
    }

    /** Descarta el audio anterior a [start]. */
    private fun keepFrom(start: Int) {
        val from = start.coerceIn(0, size)
        buffer.copyInto(buffer, 0, from, size)
        size -= from
        offset -= from
        if (speechStart >= 0) speechStart = maxOf(0, speechStart - from)
    }

    private fun decode(samples: FloatArray): Pair<String, String>? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim().replace(cjkSpaces, "")
            if (text.none { it.isLetter() }) return null
            // SenseVoice da el idioma como «<|ja|>»; el cantonés se traduce como chino.
            val lang = result.lang.removePrefix("<|").removeSuffix("|>").let { if (it == "yue") "zh" else it }
            return text to lang
        } finally {
            stream.release()
        }
    }

    override fun close() {
        running = false
        worker.join(2000)
    }
}

/** Vosk: para los idiomas que SenseVoice no reconoce (francés, alemán, italiano…). */
class VoskEngine(
    modelDir: File,
    private val language: Language,
    private val callback: SpeechCallback,
) : SpeechEngine {

    companion object {
        /** Si alguien habla sin pausas, se corta la frase a partir de esta longitud. */
        private const val MAX_PARTIAL_CHARS = 140
    }

    private val model = Model(modelDir.path)
    private val recognizer = Recognizer(model, SpeechEngine.SAMPLE_RATE.toFloat())
    private var lastPartial = ""
    @Volatile
    override var lastActivity = System.currentTimeMillis()
        private set

    override fun accept(samples: ShortArray, n: Int) {
        lastActivity = System.currentTimeMillis()
        if (recognizer.acceptWaveForm(samples, n)) {
            emitFinal(JSONObject(recognizer.result).optString("text"))
            return
        }
        val partial = JSONObject(recognizer.partialResult).optString("partial")
        if (partial.length > MAX_PARTIAL_CHARS) {
            emitFinal(JSONObject(recognizer.finalResult).optString("text"))
            recognizer.reset()
        } else if (partial != lastPartial) {
            lastPartial = partial
            if (partial.isNotBlank()) callback(clean(partial), language.code, false)
        }
    }

    private fun emitFinal(text: String) {
        lastPartial = ""
        if (text.isNotBlank()) callback(clean(text), language.code, true)
    }

    /** Vosk separa con espacios también los idiomas que no los usan. */
    private fun clean(text: String) = if (language.lineSeparator.isEmpty()) text.replace(" ", "") else text.trim()

    override fun close() {
        recognizer.close()
        model.close()
    }
}
