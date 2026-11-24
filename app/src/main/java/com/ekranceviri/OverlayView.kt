package com.ekranceviri

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max

class OverlayView(context: Context) : View(context) {

    class Item(
        val rect: Rect,
        val text: String,
        val bg: Int,
        val fg: Int,
        val lines: Int,
        val centered: Boolean
    )

    private class Drawn(val rect: Rect, val bg: Int, val layout: StaticLayout)

    private var drawn: List<Drawn> = emptyList()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun buildLayout(
        text: String, paint: TextPaint, size: Float, width: Int,
        align: Layout.Alignment, maxLines: Int
    ): StaticLayout {
        paint.textSize = size
        val b = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(align)
        if (maxLines != Int.MAX_VALUE) {
            b.setMaxLines(maxLines)
            b.setEllipsize(TextUtils.TruncateAt.END)
        }
        return b.build()
    }

    fun setItems(items: List<Item>) {
        val result = ArrayList<Drawn>()
        for (item in items) {
            val r = Rect(item.rect)
            r.inset(-3, -2)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = item.fg
            val align = if (item.centered) Layout.Alignment.ALIGN_CENTER
            else Layout.Alignment.ALIGN_NORMAL
            val startSize = (r.height().toFloat() / max(1, item.lines)) * 0.72f
            val minSize = startSize * 0.55f
            var size = startSize
            var layout = buildLayout(item.text, tp, size, r.width(), align, Int.MAX_VALUE)
            while (layout.height > r.height() && size > minSize) {
                size -= 1f
                layout = buildLayout(item.text, tp, size, r.width(), align, Int.MAX_VALUE)
            }
            if (layout.height > r.height()) {
                val lineH = max(1, layout.height / max(1, layout.lineCount))
                val maxLines = max(1, r.height() / lineH)
                layout = buildLayout(item.text, tp, size, r.width(), align, maxLines)
            }
            result.add(Drawn(r, item.bg, layout))
        }
        drawn = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (d in drawn) {
            bgPaint.color = d.bg
            canvas.drawRoundRect(
                d.rect.left.toFloat(), d.rect.top.toFloat(),
                d.rect.right.toFloat(), d.rect.bottom.toFloat(),
                6f, 6f, bgPaint
            )
            val dy = max(0, (d.rect.height() - d.layout.height) / 2)
            canvas.save()
            canvas.translate(d.rect.left.toFloat(), (d.rect.top + dy).toFloat())
            d.layout.draw(canvas)
            canvas.restore()
        }
    }
}
