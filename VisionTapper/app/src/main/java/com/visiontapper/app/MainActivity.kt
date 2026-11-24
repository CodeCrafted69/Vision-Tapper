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

    private fun summary(t: Target): String = when (t.type) {
        TargetType.IMAGE -> "Tap image • match ≥ ${t.threshold}%" + if (t.tapAll) " • all matches" else ""
        TargetType.NUM_IMAGES -> "Numbered images • ${t.startNum}→${if (t.maxNum > 0) t.maxNum.toString() else "∞"} • match ≥ ${t.threshold}%"
        TargetType.NUM_AREA -> "Numbers in area • ${t.startNum}→${if (t.maxNum > 0) t.maxNum.toString() else "∞"}"
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

        // scan interval
        root.addView(tv("Scan interval (ms, min 350)", 13f))
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
        val targets = Store.load(this)
        if (targets.isEmpty()) {
            root.addView(tv("None yet. Open any app, expand the floating panel and press ＋ to select an image or number area from your screen."))
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field(box, "Name", t.name, false)
        val thr = if (t.type != TargetType.NUM_AREA)
            field(box, "Match threshold % (lower = looser, 70–90 typical)", t.threshold.toString(), true) else null
        val start = if (t.type != TargetType.IMAGE) field(box, "Start number", t.startNum.toString(), true) else null
        val max = if (t.type != TargetType.IMAGE) field(box, "Max number (0 = unlimited)", t.maxNum.toString(), true) else null
        val loop = if (t.type != TargetType.IMAGE) check(box, "Loop back to start after max", t.loop) else null
        val chain = if (t.type != TargetType.IMAGE) check(box, "Chain: tap several numbers per scan (layout must not move)", t.chain) else null
        val delay = field(box, "Delay after each tap (ms)", t.tapDelayMs.toString(), true)
        val cool = if (t.type == TargetType.IMAGE) field(box, "Cooldown between taps (ms)", t.cooldownMs.toString(), true) else null
        val all = if (t.type == TargetType.IMAGE) check(box, "Tap every match, not just the best", t.tapAll) else null

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
                t.tapDelayMs = (delay.text.toString().toIntOrNull() ?: t.tapDelayMs).coerceAtLeast(0)
                cool?.let { t.cooldownMs = (it.text.toString().toIntOrNull() ?: t.cooldownMs).coerceAtLeast(0) }
                all?.let { t.tapAll = it.isChecked }
                Store.save(this, list)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
