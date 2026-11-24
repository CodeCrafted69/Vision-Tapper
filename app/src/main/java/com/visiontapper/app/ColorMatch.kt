package com.visiontapper.app

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

object ColorMatch {
    class Blob(val box: Rect, val area: Int) {
        val cx: Float get() = box.exactCenterX()
        val cy: Float get() = box.exactCenterY()
    }

    fun near(p: Int, c: Int, tol: Int): Boolean =
        abs(((p shr 16) and 255) - ((c shr 16) and 255)) <= tol &&
            abs(((p shr 8) and 255) - ((c shr 8) and 255)) <= tol &&
            abs((p and 255) - (c and 255)) <= tol

    /** Fraction (0..1) of sampled pixels inside [r] that are close to [color]. */
    fun coverage(bmp: Bitmap, r: Rect, color: Int, tol: Int): Float {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val rr = r.right.coerceIn(l + 1, bmp.width)
        val bb = r.bottom.coerceIn(t + 1, bmp.height)
        val step = max(1, max(rr - l, bb - t) / 40)
        var hit = 0
        var tot = 0
        var y = t
        while (y < bb) {
            var x = l
            while (x < rr) {
                if (near(bmp.getPixel(x, y), color, tol)) hit++
                tot++
                x += step
            }
            y += step
        }
        return if (tot == 0) 0f else hit / tot.toFloat()
    }

    /** Connected regions of [color] on screen, biggest first. [minPx] = minimum width/height in pixels. */
    fun blobs(bmp: Bitmap, color: Int, tol: Int, minPx: Int): List<Blob> {
        val f = max(1, bmp.width / 270)
        val w = bmp.width / f
        val h = bmp.height / f
        val mask = BooleanArray(w * h)
        val row = IntArray(bmp.width)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, bmp.width, 0, y * f + f / 2, bmp.width, 1)
            for (x in 0 until w) mask[y * w + x] = near(row[x * f + f / 2], color, tol)
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val minCells = max(2, (minPx / f) * (minPx / f))
        val out = ArrayList<Blob>()
        for (s in 0 until w * h) {
            if (!mask[s] || seen[s]) continue
            var sp = 0
            stack[sp++] = s
            seen[s] = true
            var minX = w; var maxX = -1; var minY = h; var maxY = -1; var area = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (area >= minCells) out.add(Blob(Rect(minX * f, minY * f, (maxX + 1) * f, (maxY + 1) * f), area))
        }
        out.sortByDescending { it.area }
        return out
    }
}
