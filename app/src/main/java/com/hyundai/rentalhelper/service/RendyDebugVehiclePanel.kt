package com.hyundai.rentalhelper.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.hyundai.rentalhelper.intent.DrivingState
import com.hyundai.rentalhelper.service.DebugVehicleControl.Gear

/**
 * DEBUG builds only: a small "DEBUG" chip (bottom-left, over the media widget, clear of the voice status pill,
 * the subtitle banner and the system PRND) that expands into a gear/speed panel. Every change becomes a DEMO
 * vehicle signal through [VehicleSdkManager.setDemoMotion], so the real safety policy reacts to it; "DEMO 해제"
 * returns to the real SDK signals. It never changes the vehicle.
 */
class RendyDebugVehiclePanel(
    context: Context,
    private val windowManager: WindowManager,
    private val onDemoMotion: (gearSignal: String, speedKmh: Int) -> Unit,
    private val onClearDemo: () -> Unit
) {
    private val ctx = context
    private val density = context.resources.displayMetrics.density
    private var expanded = false
    private var attached = false
    private var gear = Gear.P
    private var speed = 0
    private var demoActive = false

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.argb(240, 17, 24, 39))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), Color.rgb(245, 158, 11))
        }
        elevation = dp(10).toFloat()
        contentDescription = "RENDY debug vehicle panel"
    }

    private val chip = TextView(context).apply {
        text = "DEBUG"
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(245, 158, 11))
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setOnClickListener { toggle() }
    }
    private val status = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val gearButtons = LinkedHashMap<Gear, Button>()
    private val speedLabel = TextView(context).apply {
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        minWidth = dp(84)
    }
    private val slider = SeekBar(context)

    init {
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(chip)
        header.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(header)

        val gears = row()
        Gear.entries.forEach { g ->
            val b = button(g.label, 18f) { selectGear(g) }
            gearButtons[g] = b
            gears.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        }
        body.addView(gears, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val speedRow = row()
        speedRow.addView(speedLabel)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { speed = DebugVehicleControl.clampSpeed(gear, progress); render() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = apply("slider")
        })
        speedRow.addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
        body.addView(speedRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val presets = row()
        DebugVehicleControl.PRESETS.forEach { p ->
            presets.addView(button(p.label, 13f) {
                gear = p.gear
                speed = DebugVehicleControl.clampSpeed(p.gear, p.speedKmh)
                apply("preset ${p.label}")
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        }
        body.addView(presets, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        body.addView(button("DEMO 해제 (실제 SDK 신호로)", 13f) {
            demoActive = false
            Log.i(TAG, "Panel: DEMO cleared")
            onClearDemo()
            render()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })

        root.addView(body, LinearLayout.LayoutParams(dp(PANEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
        render()
    }

    fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(12)
        }
        try {
            windowManager.addView(root, params)
            attached = true
            Log.i(TAG, "Debug vehicle panel attached (DEBUG build only)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach debug vehicle panel", e)
        }
    }

    fun detach() {
        if (!attached) return
        try { windowManager.removeView(root) } catch (e: Exception) { Log.w(TAG, "Debug panel removal failed: ${e.message}") }
        attached = false
    }

    /** Current decision used by the safety policy (DEMO or real SDK), shown so it can be compared with the system PRND. */
    fun showMotion(motion: VehicleMotion) {
        lastMotion = motion
        render()
    }

    private var lastMotion: VehicleMotion? = null

    private fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        Log.i(TAG, "Debug panel ${if (expanded) "expanded" else "collapsed"}")
        render()
    }

    private fun selectGear(g: Gear) {
        gear = g
        speed = DebugVehicleControl.clampSpeed(g, speed)
        apply("gear ${g.label}")
    }

    private fun apply(why: String) {
        demoActive = true
        Log.i(TAG, "Panel ($why): DEMO gear=${gear.signal}, speed=$speed km/h")
        onDemoMotion(gear.signal, speed)
        render()
    }

    private fun render() {
        val m = lastMotion
        val decision = m?.let { "${label(it.state)} (${if (it.source == MotionSource.DEMO) "DEMO" else it.source.name})" } ?: "?"
        status.text = if (expanded) {
            if (demoActive) "DEMO ${gear.label} · $speed km/h → $decision" else "실제 SDK 신호 → $decision"
        } else {
            if (demoActive) "DEMO ${gear.label}·$speed" else ""
        }
        gearButtons.forEach { (g, b) ->
            val on = demoActive && g == gear
            (b.background as GradientDrawable).setColor(if (on) Color.rgb(245, 158, 11) else Color.rgb(55, 65, 81))
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        slider.max = DebugVehicleControl.maxSpeed(gear).coerceAtLeast(1)
        slider.progress = speed
        slider.isEnabled = DebugVehicleControl.speedAdjustable(gear)
        slider.alpha = if (slider.isEnabled) 1f else 0.35f
        speedLabel.text = "$speed km/h"
    }

    private fun label(s: DrivingState) = when (s) {
        DrivingState.PARKED -> "주차: 상세 안내 허용"
        DrivingState.DRIVING -> "주행/정차: 상세 안내 보류"
        DrivingState.UNKNOWN -> "알 수 없음: 보류"
    }

    private fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun button(text: String, size: Float, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        setPadding(dp(2), 0, dp(2), 0)
        minHeight = 0
        minimumHeight = 0
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(55, 65, 81)) }
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val TAG = "RendyDebugPanel"
        private const val PANEL_WIDTH_DP = 400
    }
}
