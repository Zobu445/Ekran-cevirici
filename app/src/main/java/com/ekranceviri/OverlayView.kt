package com.ekranceviri

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

class OverlayView(context: Context) : View(context) {

    class Item(val rect: Rect, val text: String, val bg: Int, val lines: Int)

    private class Drawn(val rect: Rect, val bg: Int, val layout: StaticLayout)

    private var drawn: List<Drawn> = emptyList()
    private val bgPaint = Paint()

    fun setItems(items: List<Item>) {
        drawn = items.map { item ->
            val r = Rect(item.rect)
            r.inset(-4, -2)
            val lum = 0.299 * Color.red(item.bg) + 0.587 * Color.green(item.bg) + 0.114 * Color.blue(item.bg)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = if (lum > 150) 0xFF111111.toInt() else Color.WHITE
            var size = (r.height().toFloat() / max(1, item.lines)) * 0.75f
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder
                    .obtain(item.text, 0, item.text.length, tp, max(1, r.width()))
                    .build()
                if (layout.height <= r.height() || size <= 14f) break
                size -= 1f
            }
            Drawn(r, item.bg, layout)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (d in drawn) {
            bgPaint.color = d.bg
            canvas.drawRect(d.rect, bgPaint)
            canvas.save()
            canvas.translate(d.rect.left.toFloat(), d.rect.top.toFloat())
            d.layout.draw(canvas)
            canvas.restore()
        }
    }
}
