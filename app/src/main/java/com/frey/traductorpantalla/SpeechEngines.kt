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

/** Texto reconocido: [languageCode] es el idioma detectado; [isFinal], si la frase terminó. */
typealias SpeechCallback = (text: String, languageCode: String, isFinal: Boolean) -> Unit

/** Convierte el audio (16 kHz, mono) en texto. Todo se llama desde el hilo de audio. */
interface SpeechEngine {
    fun accept(samples: ShortArray, n: Int)
    fun close()

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

/**
 * SenseVoice (sherpa-onnx): japonés, chino, coreano e inglés, con detección
 * del idioma. No es de flujo continuo, así que un detector de voz (Silero)
 * separa las frases y, mientras alguien habla, se vuelve a reconocer lo dicho
 * cada medio segundo para ir mostrando el texto.
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
        private const val PARTIAL_EVERY_MS = 500L
        /** A veces separa con espacios las palabras en japonés o chino, que no los llevan. */
        private val cjkSpaces = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー])")
    }

    private val vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                // Las frases largas se cortan para que el subtítulo no tarde demasiado.
                maxSpeechDuration = 8f,
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
                numThreads = 2,
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

    override fun accept(samples: ShortArray, n: Int) {
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        for (i in 0 until n) buffer[size + i] = samples[i] / 32768f
        size += n

        while (offset + WINDOW <= size) {
            vad.acceptWaveform(buffer.copyOfRange(offset, offset + WINDOW))
            offset += WINDOW
            if (speechStart < 0 && vad.isSpeechDetected()) {
                speechStart = maxOf(0, offset - LEAD_IN)
                lastDecode = System.currentTimeMillis()
            }
        }

        val now = System.currentTimeMillis()
        if (speechStart >= 0 && now - lastDecode > PARTIAL_EVERY_MS) {
            lastDecode = now
            decode(buffer.copyOfRange(speechStart, offset))?.let { (text, lang) -> callback(text, lang, false) }
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
        recognizer.release()
        vad.release()
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

    override fun accept(samples: ShortArray, n: Int) {
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
