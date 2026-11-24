package com.visiontapper.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/** Simple, dependency-free template matcher (mean absolute difference on a downscaled grayscale image). */
object Matcher {
    class Gray(val w: Int, val h: Int, val px: IntArray)

    class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float) {
        val cx: Float get() = x + w / 2f
        val cy: Float get() = y + h / 2f
    }

    fun gray(bmp: Bitmap, f: Int): Gray {
        val w = bmp.width / f
        val h = bmp.height / f
        val out = IntArray(w * h)
        val row = IntArray(bmp.width)
        val acc = IntArray(w)
        val div = f * f
        for (oy in 0 until h) {
            java.util.Arrays.fill(acc, 0)
            for (dy in 0 until f) {
                bmp.getPixels(row, 0, bmp.width, 0, oy * f + dy, bmp.width, 1)
                for (ox in 0 until w) {
                    var s = 0
                    val base = ox * f
                    for (dx in 0 until f) {
                        val p = row[base + dx]
                        s += (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 151 + (p and 255) * 28) shr 8
                    }
                    acc[ox] += s
                }
            }
            for (ox in 0 until w) out[oy * w + ox] = acc[ox] / div
        }
        return Gray(w, h, out)
    }

    /** Returns up to [maxHits] non-overlapping matches with similarity >= [threshold] (0..1), best first. */
    fun find(screen: Bitmap, tpl: Bitmap, threshold: Float, maxHits: Int): List<Hit> {
        val f = minOf(screen.width / 320, minOf(tpl.width, tpl.height) / 8).coerceIn(1, 8)
        val s = gray(screen, f)
        val t = gray(tpl, f)
        if (t.w < 2 || t.h < 2 || t.w > s.w || t.h > s.h) return emptyList()

        // Reject flat templates (they would "match" everywhere)
        val mean = t.px.average()
        var v = 0.0
        for (p in t.px) v += (p - mean) * (p - mean)
        if (sqrt(v / t.px.size) < 6.0) return emptyList()

        val rw = s.w - t.w + 1
        val rh = s.h - t.h + 1
        val n = t.w * t.h
        val limit = ((1f - threshold) * 255f * n).toInt()
        val score = FloatArray(rw * rh)
        val cand = ArrayList<Int>()
        val sp = s.px
        val tp = t.px

        for (y in 0 until rh) {
            for (x in 0 until rw) {
                var sum = 0
                var ty = 0
                while (ty < t.h && sum <= limit) {
                    var so = (y + ty) * s.w + x
                    var to = ty * t.w
                    for (tx in 0 until t.w) {
                        sum += abs(sp[so++] - tp[to++])
                    }
                    ty++
                }
                if (sum <= limit) {
                    val i = y * rw + x
                    score[i] = 1f - sum / (255f * n)
                    cand.add(i)
                }
            }
        }

        cand.sortByDescending { score[it] }
        val picked = ArrayList<Hit>()
        for (i in cand) {
            if (picked.size >= maxHits) break
            val x = (i % rw) * f
            val y = (i / rw) * f
            if (picked.any { abs(it.x - x) < tpl.width / 2 && abs(it.y - y) < tpl.height / 2 }) continue
            picked.add(Hit(x, y, tpl.width, tpl.height, score[i]))
        }
        return picked
    }
}
