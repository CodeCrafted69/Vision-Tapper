package com.visiontapper.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        sv.addView(root)
        setContentView(sv)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun tv(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun maxText(t: Target) = if (t.maxNum > 0) t.maxNum.toString() else "∞"

    private fun summary(t: Target): String = when (t.type) {
        TargetType.IMAGE -> "Tap image • match ≥ ${t.threshold}%" + if (t.tapAll) " • all matches" else ""
        TargetType.NUM_IMAGES -> "Numbered images • ${t.startNum}→${maxText(t)} • match ≥ ${t.threshold}%"
        TargetType.NUM_AREA -> "Numbers in area • ${t.startNum}→${maxText(t)}"
        TargetType.COLOR -> String.format("Color #%06X • tolerance %d", t.color and 0xFFFFFF, t.colorTol)
    }

    private fun render() {
        root.removeAllViews()
        root.addView(tv("Vision Tapper", 26f, true))

        val on = ClickerService.instance != null
        root.addView(
            tv(
                if (on) "● Service is running – use the floating panel on top of any app."
                else "○ Service is OFF.\n1) Settings → Accessibility → Installed apps → Vision Tapper → turn on.\n2) If it is greyed out: App info → ⋮ → “Allow restricted settings”, then try again."
            )
        )
        root.addView(btn("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("Open App info (restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        if (on) root.addView(btn("Show floating panel") { ClickerService.instance?.showPanel() })

        root.addView(tv("Scan interval (ms, min 1)", 13f))
        val scan = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.scanInterval(this@MainActivity).toString())
        }
        root.addView(scan)
        root.addView(btn("Save interval") {
            Store.setScanInterval(this, scan.text.toString().toIntOrNull() ?: 500)
            render()
        })

        root.addView(tv("Targets", 20f, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        root.addView(
            tv(
                "Loop (panel toggle): taps every copy of the current number first, then moves on, and starts over at the end.\n" +
                    "Color (panel toggle): when a color target is enabled together with other targets, those targets only fire if the color is behind them (number / image background). A color target on its own taps that color directly.",
                12f
            )
        )
        val targets = Store.load(this)
        if (targets.isEmpty()) {
            root.addView(tv("None yet. Open any app, expand the floating panel and press ＋ to select an image, number area or color from your screen."))
        }
        for (t in targets) addTargetRow(t)
    }

    private fun addTargetRow(t: Target) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        if (t.hasImage) {
            val iv = ImageView(this)
            BitmapFactory.decodeFile(Store.imageFile(this, t.id).path)?.let { iv.setImageBitmap(it) }
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) }
            row.addView(iv)
        } else if (t.type == TargetType.COLOR) {
            val sw = View(this)
            sw.setBackgroundColor(t.color or (0xFF shl 24))
            sw.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) }
            row.addView(sw)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(tv(t.name, 16f, true))
        col.addView(tv(summary(t), 12f))
        row.addView(col)
        val sw = Switch(this).apply {
            isChecked = t.enabled
            setOnCheckedChangeListener { _, c ->
                val l = Store.load(this@MainActivity)
                l.firstOrNull { it.id == t.id }?.enabled = c
                Store.save(this@MainActivity, l)
            }
        }
        row.addView(sw)
        root.addView(row)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("Edit") { edit(t) })
        actions.addView(btn("Delete") {
            AlertDialog.Builder(this)
                .setMessage("Delete “${t.name}”?")
                .setPositiveButton("Delete") { _, _ ->
                    val l = Store.load(this)
                    l.removeAll { it.id == t.id }
                    Store.save(this, l)
                    Store.imageFile(this, t.id).delete()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        root.addView(actions)
    }

    private fun field(box: LinearLayout, label: String, value: String, numeric: Boolean): EditText {
        box.addView(tv(label, 12f))
        val e = EditText(this).apply {
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(e)
        return e
    }

    private fun check(box: LinearLayout, label: String, value: Boolean): CheckBox {
        val c = CheckBox(this).apply { text = label; isChecked = value }
        box.addView(c)
        return c
    }

    private fun edit(t0: Target) {
        val list = Store.load(this)
        val t = list.firstOrNull { it.id == t0.id } ?: return
        val isNum = t.type == TargetType.NUM_IMAGES || t.type == TargetType.NUM_AREA
        val isColor = t.type == TargetType.COLOR
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field(box, "Name", t.name, false)
        val thr = if (t.type == TargetType.IMAGE || t.type == TargetType.NUM_IMAGES)
            field(box, "Match threshold % (lower = looser, 70–90 typical)", t.threshold.toString(), true) else null
        val start = if (isNum) field(box, "Start number", t.startNum.toString(), true) else null
        val max = if (isNum) field(box, "Max number (0 = unlimited)", t.maxNum.toString(), true) else null
        val loop = if (isNum) check(box, "Loop back to start after max", t.loop) else null
        val chain = if (isNum) check(box, "Chain: tap several numbers per scan (layout must not move)", t.chain) else null
        val tol = if (isColor) field(box, "Color tolerance 0–255 (higher = looser)", t.colorTol.toString(), true) else null
        val cov = if (isColor) field(box, "As background filter: min % of the number/image box in this color", t.minCoverage.toString(), true) else null
        val minSize = if (isColor) field(box, "Standalone: min blob size (px)", t.minSize.toString(), true) else null
        val delay = field(box, "Delay after each tap (ms)", t.tapDelayMs.toString(), true)
        val cool = if (t.type == TargetType.IMAGE || isColor) field(box, "Cooldown between taps (ms)", t.cooldownMs.toString(), true) else null
        val all = if (t.type == TargetType.IMAGE || isColor) check(box, "Tap every match, not just the best", t.tapAll) else null
        val useColor = if (!isColor) check(box, "Obey color targets (only fire when the color is behind it)", t.useColor) else null

        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Edit target")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                t.name = name.text.toString().ifBlank { t.name }
                thr?.let { t.threshold = (it.text.toString().toIntOrNull() ?: t.threshold).coerceIn(30, 100) }
                start?.let { t.startNum = it.text.toString().toIntOrNull() ?: t.startNum }
                max?.let { t.maxNum = (it.text.toString().toIntOrNull() ?: t.maxNum).coerceAtLeast(0) }
                loop?.let { t.loop = it.isChecked }
                chain?.let { t.chain = it.isChecked }
                tol?.let { t.colorTol = (it.text.toString().toIntOrNull() ?: t.colorTol).coerceIn(0, 255) }
                cov?.let { t.minCoverage = (it.text.toString().toIntOrNull() ?: t.minCoverage).coerceIn(1, 100) }
                minSize?.let { t.minSize = (it.text.toString().toIntOrNull() ?: t.minSize).coerceAtLeast(1) }
                t.tapDelayMs = (delay.text.toString().toIntOrNull() ?: t.tapDelayMs).coerceAtLeast(0)
                cool?.let { t.cooldownMs = (it.text.toString().toIntOrNull() ?: t.cooldownMs).coerceAtLeast(0) }
                all?.let { t.tapAll = it.isChecked }
                useColor?.let { t.useColor = it.isChecked }
                Store.save(this, list)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
