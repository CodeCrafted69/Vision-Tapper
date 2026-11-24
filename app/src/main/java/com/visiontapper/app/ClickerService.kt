package com.visiontapper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs

class ClickerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickerService? = null
    }

    private class NumItem(val num: Int, val x: Float, val y: Float, val box: Rect)

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var startBtn: TextView? = null
    private var status: TextView? = null
    private val collapsible = ArrayList<View>()
    private var cropFrame: View? = null

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nextNums = HashMap<String, Int>()
    private val lastTap = HashMap<String, Long>()
    private val tplCache = HashMap<String, Pair<Long, Bitmap>>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @Volatile private var lastShotError = 0

    private val running: Boolean get() = job?.isActive == true

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopEngine()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        cropFrame?.let { runCatching { wm.removeView(it) } }
        cropFrame = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ───────────────────────── floating panel ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(txt: String, size: Float = 14f, click: (() -> Unit)? = null) = TextView(this).apply {
        text = txt
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        if (click != null) setOnClickListener { click() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setStatus(s: String) {
        status?.text = s
    }

    private fun chip(name: String, get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        val v = tv("", 12f)
        fun paint() {
            val on = get()
            v.text = "$name: ${if (on) "ON" else "OFF"}"
            v.background = GradientDrawable().apply {
                setColor(if (on) 0xFF00897B.toInt() else 0xFF424242.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        v.setOnClickListener { set(!get()); paint() }
        paint()
        return v
    }

    fun showPanel() {
        if (panel != null) {
            panel?.visibility = View.VISIBLE
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6202124.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = tv("◉", 18f)
        val start = tv("▶ Start") { toggleEngine() }
        val add = tv("＋", 18f) { menu?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
        val app = tv("⚙", 18f) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        startBtn = start
        row.addView(handle); row.addView(start); row.addView(add); row.addView(app)

        // Toggles: Loop (tap ALL of each number, then move on) and Color (colour targets on/off)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        val lp2 = { LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) } }
        row2.addView(chip("Loop", { Store.loopAll(this) }, { Store.setLoopAll(this, it) }), lp2())
        row2.addView(chip("Color", { Store.colorOn(this) }, { Store.setColorOn(this, it) }), lp2())

        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(tv("Tap an image") { beginCapture(TargetType.IMAGE) })
            addView(tv("Numbered images 1→n") { beginCapture(TargetType.NUM_IMAGES) })
            addView(tv("Numbers in an area 1→n") { beginCapture(TargetType.NUM_AREA) })
            addView(tv("Pick a color") { beginColorPick() })
        }
        menu = m
        val st = TextView(this).apply {
            text = "Idle"
            textSize = 11f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(dp(10), 0, dp(10), dp(4))
        }
        status = st

        root.addView(row); root.addView(row2); root.addView(m); root.addView(st)
        collapsible.clear()
        collapsible.addAll(listOf(start, add, app, row2, m, st))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(140)
        }

        // Drag by the ◉ handle; tap it to collapse / expand.
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - sx) > dp(6) || abs(e.rawY - sy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = ox + (e.rawX - sx).toInt()
                            lp.y = oy + (e.rawY - sy).toInt()
                            wm.updateViewLayout(root, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        val hide = start.visibility == View.VISIBLE
                        collapsible.forEach { it.visibility = if (hide) View.GONE else View.VISIBLE }
                        if (!hide) m.visibility = View.GONE
                    }
                }
                return true
            }
        })

        wm.addView(root, lp)
        panel = root
    }

    // ───────────────────────── engine ─────────────────────────

    private fun toggleEngine() = if (running) stopEngine() else startEngine()

    private fun startEngine() {
        if (Store.load(this).none { it.enabled }) {
            toast("No enabled targets – tap ＋ to add one")
            return
        }
        nextNums.clear()
        lastTap.clear()
        menu?.visibility = View.GONE
        startBtn?.text = "■ Stop"
        setStatus("Running…")
        job = scope.launch { loop() }
    }

    private fun stopEngine() {
        job?.cancel()
        job = null
        startBtn?.text = "▶ Start"
        setStatus("Stopped")
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                val all = Store.load(this).filter { it.enabled }
                val colorOn = Store.colorOn(this)
                val colors = if (colorOn) all.filter { it.type == TargetType.COLOR } else emptyList()
                val others = all.filter { it.type != TargetType.COLOR }
                // Colour targets act as a background filter when other targets are running with them.
                val combined = colors.isNotEmpty() && others.any { it.useColor }
                val targets = if (colorOn) all else others

                val shot = screenshot()
                if (shot == null) {
                    if (lastShotError == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        delay(15) // Android allows ~3 screenshots/sec; retry as soon as it does
                        continue
                    }
                    setStatus("Screenshot failed (secure screen?)")
                    delay(700)
                    continue
                }
                for (t in targets) processTarget(t, shot, colors, combined)
                shot.recycle()
                delay(Store.scanInterval(this).toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                delay(1000)
            }
        }
    }

    private fun padded(b: Rect): Rect {
        val dx = (b.width() * 0.25f).toInt()
        val dy = (b.height() * 0.25f).toInt()
        return Rect(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy)
    }

    /** True if no colour filter applies to [t], or [box] is (mostly) one of the picked colours. */
    private fun colorOk(t: Target, shot: Bitmap, box: Rect, colors: List<Target>, combined: Boolean): Boolean {
        if (!combined || !t.useColor) return true
        return colors.any { ColorMatch.coverage(shot, box, it.color, it.colorTol) >= it.minCoverage / 100f }
    }

    private suspend fun processTarget(t: Target, shot: Bitmap, colors: List<Target>, combined: Boolean) {
        when (t.type) {
            TargetType.IMAGE -> {
                val tpl = loadTpl(t) ?: return
                val now = SystemClock.elapsedRealtime()
                if (now - (lastTap[t.id] ?: 0L) < t.cooldownMs) return
                val want = if (t.tapAll || combined) 20 else 1
                val hits = withContext(Dispatchers.Default) {
                    Matcher.find(shot, tpl, t.threshold / 100f, want)
                }.filter { colorOk(t, shot, Rect(it.x, it.y, it.x + it.w, it.y + it.h), colors, combined) }
                val use = if (t.tapAll) hits else hits.take(1)
                for (h in use) {
                    tap(h.cx, h.cy)
                    setStatus("Tapped “${t.name}” (${(h.score * 100).toInt()}%)")
                    delay(t.tapDelayMs.toLong())
                }
                if (use.isNotEmpty()) lastTap[t.id] = now
            }

            TargetType.COLOR -> {
                if (combined) return // acting as a filter for the other targets
                val now = SystemClock.elapsedRealtime()
                if (now - (lastTap[t.id] ?: 0L) < t.cooldownMs) return
                val blobs = withContext(Dispatchers.Default) {
                    ColorMatch.blobs(shot, t.color, t.colorTol, t.minSize)
                }
                val use = if (t.tapAll) blobs.take(20) else blobs.take(1)
                for (b in use) {
                    tap(b.cx, b.cy)
                    setStatus("Tapped color “${t.name}”")
                    delay(t.tapDelayMs.toLong())
                }
                if (use.isNotEmpty()) lastTap[t.id] = now
            }

            TargetType.NUM_IMAGES, TargetType.NUM_AREA -> {
                val loopAll = Store.loopAll(this)
                val wrap = t.loop || loopAll
                var next = nextNums.getOrPut(t.id) { t.startNum }
                if (t.maxNum > 0 && next > t.maxNum) {
                    if (wrap) next = t.startNum else { setStatus("“${t.name}” finished"); return }
                }
                val items = readNumbers(t, shot).filter { colorOk(t, shot, it.box, colors, combined) }
                // Loop mode: once every visible number is behind us, start over at the first number.
                if (loopAll && items.isNotEmpty() && items.none { it.num >= next }) next = t.startNum

                var guard = 0
                while (guard++ < 300) {
                    val same = items.filter { it.num == next }
                    if (same.isEmpty()) break
                    // Loop mode taps EVERY copy of this number before moving to the next one.
                    val batch = if (loopAll) same else same.take(1)
                    for (item in batch) {
                        tap(item.x, item.y)
                        delay(t.tapDelayMs.toLong())
                    }
                    setStatus("Tapped $next ×${batch.size} (“${t.name}”)")
                    next++
                    if (t.maxNum > 0 && next > t.maxNum) {
                        if (wrap) next = t.startNum
                        break
                    }
                    if (!(t.chain || loopAll)) break
                }
                nextNums[t.id] = next
            }
        }
    }

    private suspend fun readNumbers(t: Target, shot: Bitmap): List<NumItem> {
        val out = ArrayList<NumItem>()
        if (t.type == TargetType.NUM_AREA) {
            val r = t.region ?: return out
            val l = r[0].coerceIn(0, shot.width - 1)
            val tp = r[1].coerceIn(0, shot.height - 1)
            val w = (r[2] - l).coerceIn(1, shot.width - l)
            val h = (r[3] - tp).coerceIn(1, shot.height - tp)
            val crop = Bitmap.createBitmap(shot, l, tp, w, h)
            val text = ocr(crop) ?: return out
            for (b in text.textBlocks) for (ln in b.lines) for (el in ln.elements) {
                val s = el.text.trim()
                val box = el.boundingBox ?: continue
                if (s.length in 1..4 && s.all { it.isDigit() }) {
                    val sb = padded(Rect(l + box.left, tp + box.top, l + box.right, tp + box.bottom))
                    out.add(NumItem(s.toInt(), l + box.exactCenterX(), tp + box.exactCenterY(), sb))
                }
            }
        } else {
            val tpl = loadTpl(t) ?: return out
            val hits = withContext(Dispatchers.Default) {
                Matcher.find(shot, tpl, t.threshold / 100f, 60)
            }
            val digits = Regex("\\d{1,4}")
            for (h in hits) {
                val l = h.x.coerceIn(0, shot.width - 1)
                val tp = h.y.coerceIn(0, shot.height - 1)
                val w = h.w.coerceIn(1, shot.width - l)
                val hh = h.h.coerceIn(1, shot.height - tp)
                var crop = Bitmap.createBitmap(shot, l, tp, w, hh)
                if (hh < 80) crop = Bitmap.createScaledBitmap(crop, w * 3, hh * 3, true)
                val txt = ocr(crop)?.text ?: continue
                val m = digits.find(txt.replace(Regex("\\s"), "")) ?: continue
                out.add(NumItem(m.value.toInt(), h.cx, h.cy, Rect(l, tp, l + w, tp + hh)))
            }
        }
        return out
    }

    private fun loadTpl(t: Target): Bitmap? {
        val f = Store.imageFile(this, t.id)
        if (!f.exists()) return null
        val cached = tplCache[t.id]
        if (cached != null && cached.first == f.lastModified()) return cached.second
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        tplCache[t.id] = Pair(f.lastModified(), bmp)
        return bmp
    }

    // ───────────────────────── system helpers ─────────────────────────

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                lastShotError = 0
                c.resume(bmp)
            }

            override fun onFailure(errorCode: Int) {
                lastShotError = errorCode
                c.resume(null)
            }
        })
    }

    private suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { c ->
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { c.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { c.resume(false) }
        }, null)
        if (!ok) c.resume(false)
    }

    private suspend fun ocr(bmp: Bitmap): Text? = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resume(null) }
    }

    // ───────────────────────── capture / crop / colour-pick flows ─────────────────────────

    private fun freezeScreen(then: (Bitmap) -> Unit) {
        if (running) stopEngine()
        menu?.visibility = View.GONE
        panel?.visibility = View.INVISIBLE
        scope.launch {
            delay(400)
            val shot = screenshot()
            panel?.visibility = View.VISIBLE
            if (shot == null) toast("Couldn't capture the screen") else then(shot)
        }
    }

    private fun beginCapture(kind: TargetType) = freezeScreen { shot ->
        val cv = CropView(this, shot)
        val hint = when (kind) {
            TargetType.IMAGE -> "Drag a box around the image to tap"
            TargetType.NUM_IMAGES -> "Drag a box around ONE numbered tile/button"
            TargetType.NUM_AREA -> "Drag a box around the area with the numbers"
            TargetType.COLOR -> ""
        }
        showOverlay(cv, hint) {
            val r = cv.selection()
            if (r == null) {
                toast("Drag a bigger box first"); false
            } else {
                saveCapture(shot, r, kind); true
            }
        }
    }

    private fun beginColorPick() = freezeScreen { shot ->
        val pv = ColorPickView(this, shot)
        showOverlay(pv, "Tap the color you want to detect") {
            val c = pv.picked
            if (c == null) {
                toast("Tap a color first"); false
            } else {
                saveColor(c); true
            }
        }
    }

    private fun showOverlay(content: View, hint: String, onSave: () -> Boolean) {
        val frame = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(tv(hint, 14f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun close() {
            runCatching { wm.removeView(frame) }
            cropFrame = null
        }
        btns.addView(tv("✓ Save", 16f) { if (onSave()) close() })
        btns.addView(tv("✕ Cancel", 16f) { close() })
        bar.addView(btns)
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(56)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(frame, lp)
        cropFrame = frame
    }

    private fun saveColor(color: Int) {
        val list = Store.load(this)
        val id = UUID.randomUUID().toString().take(8)
        val n = list.count { it.type == TargetType.COLOR } + 1
        list.add(Target(id = id, name = "Color $n", type = TargetType.COLOR, color = color or (0xFF shl 24)))
        Store.save(this, list)
        toast("Saved “Color $n” – tune it in the ⚙ app")
    }

    private fun saveCapture(shot: Bitmap, r: Rect, kind: TargetType) {
        val list = Store.load(this)
        val id = UUID.randomUUID().toString().take(8)
        val n = list.count { it.type == kind } + 1
        val label = when (kind) {
            TargetType.IMAGE -> "Image"
            TargetType.NUM_IMAGES -> "Numbered images"
            TargetType.NUM_AREA -> "Number area"
            TargetType.COLOR -> "Color"
        }
        var region: IntArray? = null
        var hasImage = false
        if (kind == TargetType.NUM_AREA) {
            region = intArrayOf(r.left, r.top, r.right, r.bottom)
        } else {
            val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
            File(Store.imageFile(this, id).path).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            hasImage = true
        }
        list.add(
            Target(
                id = id, name = "$label $n", type = kind,
                threshold = if (kind == TargetType.NUM_IMAGES) 75 else 85,
                hasImage = hasImage, region = region
            )
        )
        Store.save(this, list)
        toast("Saved “$label $n” – tune it in the ⚙ app")
    }
}
