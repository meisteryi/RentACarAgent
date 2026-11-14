package com.hyundai.rentalhelper.service

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer

/**
 * Holds the automatic startup speech (greeting, then briefing) until this app's UI thread renders frames on time
 * again, so the TTS subtitle can actually be drawn when Pleos starts playing.
 *
 * Measured on the emulator (2026-11-14): the first frames of the overlay windows created at service start
 * (bubble, subtitle banner, debug panel) took 3-5.7 s to render (OpenGLRenderer "Davey!", "Skipped 177/221
 * frames"); the greeting started playing inside that stall and its subtitle appeared 3.7 s late.
 *
 * Readiness is a condition, not a fixed delay: [STABLE_FRAMES] consecutive Choreographer frames each arriving
 * within [MAX_FRAME_INTERVAL_MS]. [MAX_WAIT_MS] is only a safety cap so the greeting is never lost. Actions run in
 * [Order] order (greeting before briefing), whatever order they were requested in.
 */
class StartupSpeechGate {
    enum class Order { GREETING, BRIEFING }

    private val pending = sortedMapOf<Order, MutableList<() -> Unit>>()
    private var ready = false
    private var running = false
    private var startedAtMs = 0L
    private var lastFrameAtMs = 0L
    private var goodFrames = 0

    fun runWhenReady(order: Order, action: () -> Unit) {
        if (ready) {
            action()
            return
        }
        pending.getOrPut(order) { mutableListOf() } += action
        if (!running) {
            running = true
            startedAtMs = SystemClock.elapsedRealtime()
            lastFrameAtMs = 0L
            goodFrames = 0
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.elapsedRealtime()
            if (lastFrameAtMs > 0L) {
                if (now - lastFrameAtMs <= MAX_FRAME_INTERVAL_MS) goodFrames++ else goodFrames = 0
            }
            lastFrameAtMs = now
            val waited = now - startedAtMs
            if (goodFrames >= STABLE_FRAMES || waited >= MAX_WAIT_MS) {
                ready = true
                running = false
                Log.i(TAG, "UI_READY for startup speech after ${waited}ms (stableFrames=$goodFrames, cap=${waited >= MAX_WAIT_MS})")
                val actions = pending.values.flatten()
                pending.clear()
                actions.forEach { it() }
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    companion object {
        private const val TAG = "RendySubtitle"
        const val STABLE_FRAMES = 3
        const val MAX_FRAME_INTERVAL_MS = 100L
        const val MAX_WAIT_MS = 6_000L
    }
}
