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

    private class NumItem(val num: Int, val x: Float, val y: Float)

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

        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(tv("Tap an image") { beginCapture(TargetType.IMAGE) })
            addView(tv("Numbered images 1→n") { beginCapture(TargetType.NUM_IMAGES) })
            addView(tv("Numbers in an area 1→n") { beginCapture(TargetType.NUM_AREA) })
        }
        menu = m
        val st = TextView(this).apply {
            text = "Idle"
            textSize = 11f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(dp(10), 0, dp(10), dp(4))
        }
        status = st

        root.addView(row); root.addView(m); root.addView(st)
        collapsible.clear()
        collapsible.addAll(listOf(start, add, app, m, st))

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
                val targets = Store.load(this).filter { it.enabled }
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
                for (t in targets) processTarget(t, shot)
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

    private suspend fun processTarget(t: Target, shot: Bitmap) {
        when (t.type) {
            TargetType.IMAGE -> {
                val tpl = loadTpl(t) ?: return
                val now = SystemClock.elapsedRealtime()
                if (now - (lastTap[t.id] ?: 0L) < t.cooldownMs) return
                val hits = withContext(Dispatchers.Default) {
                    Matcher.find(shot, tpl, t.threshold / 100f, if (t.tapAll) 20 else 1)
                }
                for (h in hits) {
                    tap(h.cx, h.cy)
                    setStatus("Tapped “${t.name}” (${(h.score * 100).toInt()}%)")
                    delay(t.tapDelayMs.toLong())
                }
                if (hits.isNotEmpty()) lastTap[t.id] = now
            }

            TargetType.NUM_IMAGES, TargetType.NUM_AREA -> {
                var next = nextNums.getOrPut(t.id) { t.startNum }
                if (t.maxNum > 0 && next > t.maxNum) {
                    if (t.loop) next = t.startNum else { setStatus("“${t.name}” finished"); return }
                }
                val items = readNumbers(t, shot)
                var guard = 0
                while (guard++ < 300) {
                    val hit = items.firstOrNull { it.num == next } ?: break
                    tap(hit.x, hit.y)
                    setStatus("Tapped $next (“${t.name}”)")
                    next++
                    if (t.maxNum > 0 && next > t.maxNum) {
                        if (t.loop) next = t.startNum
                        nextNums[t.id] = next
                        break
                    }
                    nextNums[t.id] = next
                    delay(t.tapDelayMs.toLong())
                    if (!t.chain) break
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
                    out.add(NumItem(s.toInt(), l + box.exactCenterX(), tp + box.exactCenterY()))
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
                out.add(NumItem(m.value.toInt(), h.cx, h.cy))
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

    // ───────────────────────── capture / crop flow ─────────────────────────

    private fun beginCapture(kind: TargetType) {
        if (running) stopEngine()
        menu?.visibility = View.GONE
        panel?.visibility = View.INVISIBLE
        scope.launch {
            delay(400)
            val shot = screenshot()
            panel?.visibility = View.VISIBLE
            if (shot == null) {
                toast("Couldn't capture the screen")
                return@launch
            }
            showCropper(shot, kind)
        }
    }

    private fun showCropper(shot: Bitmap, kind: TargetType) {
        val frame = FrameLayout(this)
        val cv = CropView(this, shot)
        frame.addView(cv, FrameLayout.LayoutParams(-1, -1))

        val hint = when (kind) {
            TargetType.IMAGE -> "Drag a box around the image to tap"
            TargetType.NUM_IMAGES -> "Drag a box around ONE numbered tile/button"
            TargetType.NUM_AREA -> "Drag a box around the area with the numbers"
        }
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
        btns.addView(tv("✓ Save", 16f) {
            val r = cv.selection()
            if (r == null) {
                toast("Drag a bigger box first")
            } else {
                saveCapture(shot, r, kind)
                close()
            }
        })
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

    private fun saveCapture(shot: Bitmap, r: Rect, kind: TargetType) {
        val list = Store.load(this)
        val id = UUID.randomUUID().toString().take(8)
        val n = list.count { it.type == kind } + 1
        val label = when (kind) {
            TargetType.IMAGE -> "Image"
            TargetType.NUM_IMAGES -> "Numbered images"
            TargetType.NUM_AREA -> "Number area"
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
