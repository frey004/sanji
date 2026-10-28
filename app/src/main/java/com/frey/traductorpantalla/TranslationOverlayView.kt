package com.frey.traductorpantalla

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

data class TranslatedBlock(val bounds: Rect, val text: String)

/** Dibuja cada traducción encima del texto original, en sus mismas coordenadas. */
class TranslationOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 20, 20, 24) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val location = IntArray(2)

    var blocks: List<TranslatedBlock> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        // Las coordenadas del OCR son de pantalla completa; compensamos si la
        // ventana no empieza en (0, 0) (barra de estado, recortes de cámara…).
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        val pad = 4 * density
        for (block in blocks) {
            val width = (block.bounds.width() + 2 * pad).toInt().coerceAtLeast(1)
            val layout = fitText(block.text, width - (2 * pad).toInt(), block.bounds.height())
            val height = maxOf(block.bounds.height().toFloat(), layout.height.toFloat()) + 2 * pad
            val rect = RectF(
                block.bounds.left - pad,
                block.bounds.top - pad,
                block.bounds.left - pad + width,
                block.bounds.top - pad + height,
            )
            canvas.drawRoundRect(rect, 6 * density, 6 * density, background)
            canvas.save()
            canvas.translate(rect.left + pad, rect.top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Busca el tamaño de letra más grande con el que el texto cabe en el recuadro. */
    private fun fitText(text: String, width: Int, height: Int): StaticLayout {
        val minSize = 9 * scaledDensity
        var size = minOf(height.toFloat(), 28 * scaledDensity).coerceAtLeast(minSize)
        while (true) {
            textPaint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            if (layout.height <= height || size <= minSize) return layout
            size = maxOf(minSize, size * 0.9f)
        }
    }
}
