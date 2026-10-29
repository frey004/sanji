package com.frey.traductorpantalla

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Lee el texto de una imagen (OCR de ML Kit). Con [Languages.AUTO] usa el
 * lector japonés, que también lee chino y alfabeto latino, y si no encuentra
 * kana prueba además el coreano, quedándose con el que lee más texto propio.
 */
class TextReader(source: Language) : Closeable {

    private val primary = Languages.recognizer(if (source == Languages.AUTO) Languages.JAPANESE else source)
    private val korean = if (source == Languages.AUTO) Languages.recognizer(Languages.KOREAN) else null

    /** Bloques de texto con su posición; las líneas de cada bloque van unidas. */
    suspend fun read(bitmap: Bitmap): List<Pair<Rect, String>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        var result = primary.process(image).await()
        if (korean != null && result.text.none { isKana(it) }) {
            val other = korean.process(image).await()
            if (other.text.count { isHangul(it) } > result.text.count { isHan(it) }) result = other
        }
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.map { it.text.trim() }
            // El japonés y el chino no separan las palabras con espacios.
            val separator = if (lines.any { line -> line.any { isKana(it) || isHan(it) } }) "" else " "
            box to lines.joinToString(separator)
        }
    }

    private fun isKana(c: Char) = c in '぀'..'ヿ'
    private fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'
    private fun isHangul(c: Char) = c in '가'..'힯'

    override fun close() {
        primary.close()
        korean?.close()
    }
}
