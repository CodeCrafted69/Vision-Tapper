package com.visiontapper.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shows a frozen screenshot; the user drags a rectangle over it. */
class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var has = false
    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE; color = Color.CYAN; strokeWidth = 4f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        if (!has) {
            c.drawRect(0f, 0f, w, h, dim)
            return
        }
        val l = min(x0, x1); val r = max(x0, x1)
        val t = min(y0, y1); val b = max(y0, y1)
        c.drawRect(0f, 0f, w, t, dim)
        c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim)
        c.drawRect(r, t, w, b, dim)
        c.drawRect(l, t, r, b, stroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { x0 = x; y0 = y; x1 = x; y1 = y; has = false }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { x1 = x; y1 = y; has = true }
        }
        invalidate()
        return true
    }

    /** Selection in bitmap (screen pixel) coordinates, or null if too small. */
    fun selection(): Rect? {
        if (!has || width == 0 || height == 0) return null
        val sx = bmp.width / width.toFloat()
        val sy = bmp.height / height.toFloat()
        val l = (min(x0, x1) * sx).toInt().coerceIn(0, bmp.width - 1)
        val r = (max(x0, x1) * sx).toInt().coerceIn(0, bmp.width)
        val t = (min(y0, y1) * sy).toInt().coerceIn(0, bmp.height - 1)
        val b = (max(y0, y1) * sy).toInt().coerceIn(0, bmp.height)
        if (r - l < 8 || b - t < 8) return null
        return Rect(l, t, r, b)
    }
}
