package com.hyundai.rentalhelper.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small, non-interactive voice status overlay for a wake-word initiated turn. */
class RendyVoiceStatusOverlay(
    context: Context,
    private val windowManager: WindowManager
) {
    private val appContext = context.applicationContext
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.argb(248, 255, 255, 255))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(213, 232, 248))
        }
        elevation = dp(12).toFloat()
        contentDescription = "RENDY voice status"
    }
    private val statusText = TextView(context).apply {
        text = RendyVoicePrompts.LISTENING_STATUS
        textSize = 17f
        setTextColor(Color.rgb(17, 24, 39))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
    }
    /**
     * Live STT transcript under the status (real Pleos partial/final text only). Interim text is grey, the
     * confirmed question dark; at most two lines so it stays a glance, not something to read while driving.
     */
    private val transcriptText = TextView(context).apply {
        textSize = 22f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = dp(640)
        visibility = View.GONE
    }
    private val bars = ArrayList<View>(3)
    private val animators = ArrayList<ObjectAnimator>(3)
    private var attached = false

    init {
        val micCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0, 114, 206))
            }
        }
        val micIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            contentDescription = "Microphone"
        }
        micCircle.addView(micIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        root.addView(micCircle, LinearLayout.LayoutParams(dp(46), dp(46)))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(context).apply {
            text = "RENDY"
            textSize = 10f
            setTextColor(Color.rgb(0, 114, 206))
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        labels.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        labels.addView(transcriptText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        root.addView(labels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })

        val activity = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(3) { index ->
            val bar = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(Color.rgb(0, 114, 206))
                }
                scaleY = 0.35f
                alpha = 0.55f
                pivotY = dp(12).toFloat()
            }
            activity.addView(bar, LinearLayout.LayoutParams(dp(4), dp(24)).apply {
                marginStart = if (index == 0) 0 else dp(4)
            })
            bars += bar
            animators += ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.35f, 1.0f, 0.45f).apply {
                duration = 560L
                startDelay = index * 130L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        root.addView(activity, LinearLayout.LayoutParams(dp(28), dp(32)).apply {
            marginStart = dp(14)
        })
    }

    fun show(message: String, animate: Boolean) {
        statusText.text = message
        root.contentDescription = "RENDY: $message"
        if (!attached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Bottom centre, just above the dock: the top edge is taken by the system bar and the TTS subtitle
                // banner (top-end, drawn above this window), which hid the status and the live transcript.
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(DOCK_CLEARANCE_DP)
            }
            try {
                windowManager.addView(root, params)
                attached = true
                Log.i(TAG, "Voice status overlay shown: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Could not show voice status overlay; voice flow will continue", e)
            }
        }
        setAnimating(animate)
    }

    /** Shows or clears the live transcript line; does not attach the overlay by itself. */
    fun setTranscript(view: LiveTranscriptView?) {
        if (view == null) {
            transcriptText.text = ""
            transcriptText.visibility = View.GONE
            return
        }
        transcriptText.visibility = View.VISIBLE
        when {
            view.isPlaceholder -> {
                transcriptText.text = view.text
                transcriptText.setTextColor(Color.rgb(156, 163, 175))
                transcriptText.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            view.isFinal -> {
                transcriptText.text = "“${view.text}”"
                transcriptText.setTextColor(Color.rgb(17, 24, 39))
                transcriptText.setTypeface(null, android.graphics.Typeface.BOLD)
            }
            else -> {
                transcriptText.text = view.text
                transcriptText.setTextColor(Color.rgb(75, 85, 99))
                transcriptText.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        root.contentDescription = "RENDY: ${statusText.text}, ${view.text}"
    }

    fun hide() {
        setTranscript(null)
        setAnimating(false)
        if (!attached) return
        try {
            windowManager.removeView(root)
            Log.i(TAG, "Voice status overlay dismissed")
        } catch (e: Exception) {
            Log.w(TAG, "Voice status overlay removal failed: ${e.message}")
        } finally {
            attached = false
        }
    }

    fun destroy() = hide()

    private fun setAnimating(enabled: Boolean) {
        if (enabled) {
            animators.forEach { if (!it.isStarted) it.start() }
        } else {
            animators.forEach { it.cancel() }
            bars.forEach {
                it.scaleY = 0.35f
                it.alpha = 0.55f
            }
        }
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "RendyVoiceUX"
        /** Gap above the bottom dock (the overlay frame already ends at the dock's top edge). */
        private const val DOCK_CLEARANCE_DP = 12
    }
}