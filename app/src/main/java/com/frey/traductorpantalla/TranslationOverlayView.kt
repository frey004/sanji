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

data class TranslatedBlock(val bounds: Rect, val text: String, val isDialogue: Boolean = false)

/** Colores de los recuadros de traducción. */
data class OverlayStyle(val background: Int, val text: Int, val border: Int? = null) {
    companion object {
        val DARK = OverlayStyle(Color.argb(240, 20, 20, 24), Color.WHITE)

        /** Claro y translúcido, a juego con los cuadros blancos de Uma Musume. */
        fun light(opacity: Int) = OverlayStyle(
            background = Color.argb((opacity.coerceIn(0, 100) * 255) / 100, 255, 255, 255),
            text = Color.rgb(74, 52, 40),
            border = Color.argb(200, 240, 140, 180),
        )
    }
}

/** Dibuja cada traducción encima del texto original, en sus mismas coordenadas. */
class TranslationOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)

    var blocks: List<TranslatedBlock> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var style: OverlayStyle = OverlayStyle.DARK
        set(value) {
            field = value
            background.color = value.background
            textPaint.color = value.text
            value.border?.let { borderPaint.color = it }
            // Con fondo translúcido, una sombra suave mantiene el texto legible.
            if (Color.alpha(value.background) < 200) {
                textPaint.setShadowLayer(2 * density, 0f, 0f, Color.WHITE)
            } else {
                textPaint.clearShadowLayer()
            }
            invalidate()
        }

    init {
        style = OverlayStyle.DARK
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        // Las coordenadas del OCR son de pantalla completa; compensamos si la
        // ventana no empieza en (0, 0) (barra de estado, recortes de cámara…).
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for (block in blocks) {
            val pad = (if (block.isDialogue) 8 else 4) * density
            val width = (block.bounds.width() + 2 * pad).toInt().coerceAtLeast(1)
            val maxSize = (if (block.isDialogue) 22 else 28) * scaledDensity
            val layout = fitText(block.text, width - (2 * pad).toInt(), block.bounds.height(), maxSize)
            val height = maxOf(block.bounds.height().toFloat(), layout.height.toFloat()) + 2 * pad
            val rect = RectF(
                block.bounds.left - pad,
                block.bounds.top - pad,
                block.bounds.left - pad + width,
                block.bounds.top - pad + height,
            )
            val radius = (if (block.isDialogue) 12 else 6) * density
            canvas.drawRoundRect(rect, radius, radius, background)
            if (style.border != null) canvas.drawRoundRect(rect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(rect.left + pad, rect.top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Busca el tamaño de letra más grande con el que el texto cabe en el recuadro. */
    private fun fitText(text: String, width: Int, height: Int, maxSize: Float): StaticLayout {
        val minSize = 9 * scaledDensity
        var size = minOf(height.toFloat(), maxSize).coerceAtLeast(minSize)
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
