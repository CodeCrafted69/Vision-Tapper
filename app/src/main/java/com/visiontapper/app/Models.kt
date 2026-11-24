package com.visiontapper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TargetType { IMAGE, NUM_IMAGES, NUM_AREA, COLOR }

class Target(
    val id: String,
    var name: String,
    var type: TargetType,
    var enabled: Boolean = true,
    var threshold: Int = 85,        // match similarity in percent
    var startNum: Int = 1,
    var maxNum: Int = 0,            // 0 = no upper limit
    var loop: Boolean = false,      // after maxNum go back to startNum
    var chain: Boolean = false,     // tap several consecutive numbers from one screenshot
    var tapDelayMs: Int = 300,
    var cooldownMs: Int = 1000,     // IMAGE / COLOR: minimum time between taps of this target
    var tapAll: Boolean = false,    // IMAGE / COLOR: tap every match, not only the best one
    var hasImage: Boolean = false,
    var region: IntArray? = null,   // NUM_AREA: l, t, r, b in screen pixels
    var color: Int = 0,             // COLOR: picked colour (ARGB)
    var colorTol: Int = 40,         // COLOR: per-channel tolerance 0..255
    var minCoverage: Int = 20,      // COLOR as filter: % of the number's box that must be this colour
    var minSize: Int = 20,          // COLOR standalone: minimum blob width/height in px
    var useColor: Boolean = true    // non-COLOR targets: obey active colour targets
)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("vt", Context.MODE_PRIVATE)

    fun imageFile(c: Context, id: String) = File(c.filesDir, "t_$id.png")

    fun scanInterval(c: Context): Int = p(c).getInt("scan", 500)
    fun setScanInterval(c: Context, ms: Int) = p(c).edit().putInt("scan", ms.coerceAtLeast(1)).apply()

    fun loopAll(c: Context): Boolean = p(c).getBoolean("loopAll", false)
    fun setLoopAll(c: Context, v: Boolean) = p(c).edit().putBoolean("loopAll", v).apply()

    fun colorOn(c: Context): Boolean = p(c).getBoolean("colorOn", true)
    fun setColorOn(c: Context, v: Boolean) = p(c).edit().putBoolean("colorOn", v).apply()

    fun load(c: Context): MutableList<Target> {
        val out = mutableListOf<Target>()
        val a = JSONArray(p(c).getString("targets", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val r = o.optJSONArray("region")
            out.add(
                Target(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = TargetType.valueOf(o.getString("type")),
                    enabled = o.optBoolean("enabled", true),
                    threshold = o.optInt("threshold", 85),
                    startNum = o.optInt("startNum", 1),
                    maxNum = o.optInt("maxNum", 0),
                    loop = o.optBoolean("loop", false),
                    chain = o.optBoolean("chain", false),
                    tapDelayMs = o.optInt("tapDelayMs", 300),
                    cooldownMs = o.optInt("cooldownMs", 1000),
                    tapAll = o.optBoolean("tapAll", false),
                    hasImage = o.optBoolean("hasImage", false),
                    region = r?.let { intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) },
                    color = o.optInt("color", 0),
                    colorTol = o.optInt("colorTol", 40),
                    minCoverage = o.optInt("minCoverage", 20),
                    minSize = o.optInt("minSize", 20),
                    useColor = o.optBoolean("useColor", true)
                )
            )
        }
        return out
    }

    fun save(c: Context, list: List<Target>) {
        val a = JSONArray()
        for (t in list) {
            a.put(JSONObject().apply {
                put("id", t.id); put("name", t.name); put("type", t.type.name)
                put("enabled", t.enabled); put("threshold", t.threshold)
                put("startNum", t.startNum); put("maxNum", t.maxNum)
                put("loop", t.loop); put("chain", t.chain)
                put("tapDelayMs", t.tapDelayMs); put("cooldownMs", t.cooldownMs)
                put("tapAll", t.tapAll); put("hasImage", t.hasImage)
                put("color", t.color); put("colorTol", t.colorTol)
                put("minCoverage", t.minCoverage); put("minSize", t.minSize)
                put("useColor", t.useColor)
                t.region?.let { r -> put("region", JSONArray().apply { r.forEach { v -> put(v) } }) }
            })
        }
        p(c).edit().putString("targets", a.toString()).apply()
    }
}
