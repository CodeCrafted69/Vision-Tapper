package com.visiontapper.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Frozen screenshot; tap/drag to pick the colour under your finger. */
class ColorPickView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    var picked: Int? = null
        private set
    private var px = 0f
    private var py = 0f
    private val ring = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 6f }
    private val ringDark = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 2f }
    private val fill = Paint()
    private val border = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 6f }
    private val text = Paint().apply {
        color = Color.WHITE; textSize = 52f; textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        val col = picked ?: return
        c.drawCircle(px, py, 36f, ring)
        c.drawCircle(px, py, 39f, ringDark)
        val s = 140f
        val l = width / 2f - s / 2
        val t = height - s - 240f
        fill.color = col
        c.drawRect(l, t, l + s, t + s, fill)
        c.drawRect(l, t, l + s, t + s, border)
        c.drawText(String.format("#%06X", col and 0xFFFFFF), width / 2f, t + s + 64f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        px = e.x.coerceIn(0f, width.toFloat())
        py = e.y.coerceIn(0f, height.toFloat())
        sample()
        invalidate()
        return true
    }

    private fun sample() {
        val bx = (px * bmp.width / width).toInt().coerceIn(0, bmp.width - 1)
        val by = (py * bmp.height / height).toInt().coerceIn(0, bmp.height - 1)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val x = (bx + dx).coerceIn(0, bmp.width - 1)
            val y = (by + dy).coerceIn(0, bmp.height - 1)
            val p = bmp.getPixel(x, y)
            r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
        }
        picked = Color.rgb(r / n, g / n, b / n)
    }
}
