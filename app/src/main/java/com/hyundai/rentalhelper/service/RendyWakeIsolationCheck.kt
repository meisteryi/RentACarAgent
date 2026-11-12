package com.hyundai.rentalhelper.service

import kotlin.math.sqrt

/**
 * Stage-2 check for a Vosk "렌디" candidate: the wake word must be spoken as an isolated utterance, not as a short
 * piece of ongoing conversation that the two-word grammar forced onto "렌디".
 *
 * Works on 20 ms frame RMS of the same PCM the recognizer received. The voiced region around the candidate word is
 * grown from the word (frame RMS > [VOICE_FACTOR] x local background, gaps <= 120 ms bridged; anchoring on the
 * region instead of Vosk word times keeps slow "렌-디" intact). The candidate is rejected when the region is longer
 * than [MAX_REGION_MS] or when the [WINDOW_MS] before or after the region holds at least [MAX_CONTEXT_VOICED_MS] of
 * voiced frames.
 *
 * Chosen on the device self-test (production recognizer and endpointing; reports/wakeword-fa): continuous real Korean
 * speech (FLEURS ko_kr, 2.1 h) 153.8 -> 10.9 activations/h; isolated synthetic "렌디" 79 -> 77 of 100 (both losses
 * at 10/5 dB SNR). After other speech the user needs a pause of about 0.8 s before "렌디".
 */
internal object RendyWakeIsolationCheck {
    const val FRAME_SAMPLES = 320                 // 20 ms at 16 kHz
    const val FRAME_MS = 20
    const val VOICE_FACTOR = 2.0
    const val MIN_FLOOR = 30.0
    const val BRIDGE_FRAMES = 6                   // gaps up to 120 ms stay inside the region
    const val WINDOW_MS = 600
    const val MAX_CONTEXT_VOICED_MS = 60          // voiced ms allowed in each window (exclusive)
    const val MAX_REGION_MS = 1500
    private const val BACKGROUND_BEFORE_FRAMES = 150   // 3 s
    private const val BACKGROUND_AFTER_FRAMES = 50     // 1 s (whatever is already available)

    /** Tunable parameters; [DEFAULT] is the production setting. */
    data class Params(
        val voiceFactor: Double = VOICE_FACTOR,
        val windowMs: Int = WINDOW_MS,
        val maxContextVoicedMs: Int = MAX_CONTEXT_VOICED_MS,
        val maxRegionMs: Int = MAX_REGION_MS,
        val bridgeFrames: Int = BRIDGE_FRAMES
    ) {
        val label: String get() = "k${voiceFactor}_w${windowMs}_c${maxContextVoicedMs}_r${maxRegionMs}"
    }

    val DEFAULT = Params()

    data class Result(
        val isolated: Boolean,
        val floor: Double,
        val regionMs: Int,
        val preVoicedMs: Int,
        val postVoicedMs: Int,
        val reason: String
    )

    /**
     * @param frameRms frame RMS values; index 0 = frame [firstFrame]
     * @param firstFrame absolute frame index of frameRms[0] (frames counted from the start of the recognizer stream)
     */
    fun evaluate(frameRms: DoubleArray, firstFrame: Long, wordStartSec: Double, wordEndSec: Double, params: Params = DEFAULT): Result {
        val n = frameRms.size
        if (n == 0) return Result(true, 0.0, 0, 0, 0, "no audio history")
        fun rel(sec: Double): Int = ((sec * 1000.0 / FRAME_MS).toLong() - firstFrame).toInt()
        val a = rel(wordStartSec).coerceIn(0, n - 1)
        val b = rel(wordEndSec).coerceIn(a, n - 1)
        val bgFrom = (a - BACKGROUND_BEFORE_FRAMES).coerceAtLeast(0)
        val bgTo = (b + BACKGROUND_AFTER_FRAMES).coerceAtMost(n)
        val floor = maxOf(percentile(frameRms, bgFrom, bgTo, 20.0), MIN_FLOOR)
        val threshold = params.voiceFactor * floor
        fun voiced(i: Int) = frameRms[i] > threshold

        var lo = -1; var hi = -1
        for (i in a..b) if (voiced(i)) { if (lo < 0) lo = i; hi = i }
        if (lo < 0) { lo = a; hi = a }
        var gap = 0; var i = lo - 1
        while (i >= 0) {
            if (voiced(i)) { lo = i; gap = 0 } else if (++gap > params.bridgeFrames) break
            i--
        }
        gap = 0; i = hi + 1
        while (i < n) {
            if (voiced(i)) { hi = i; gap = 0 } else if (++gap > params.bridgeFrames) break
            i++
        }
        val window = params.windowMs / FRAME_MS
        var pre = 0
        for (k in (lo - window).coerceAtLeast(0) until lo) if (voiced(k)) pre++
        var post = 0
        for (k in hi + 1 until (hi + 1 + window).coerceAtMost(n)) if (voiced(k)) post++
        val regionMs = (hi - lo + 1) * FRAME_MS
        val preMs = pre * FRAME_MS
        val postMs = post * FRAME_MS
        val reason = when {
            regionMs > params.maxRegionMs -> "voiced region ${regionMs}ms > ${params.maxRegionMs}ms"
            preMs >= params.maxContextVoicedMs -> "speech ${preMs}ms within ${params.windowMs}ms before"
            postMs >= params.maxContextVoicedMs -> "speech ${postMs}ms within ${params.windowMs}ms after"
            else -> "isolated"
        }
        return Result(reason == "isolated", floor, regionMs, preMs, postMs, reason)
    }

    /** Linear-interpolated percentile (numpy default) of values[from, to). */
    internal fun percentile(values: DoubleArray, from: Int, to: Int, p: Double): Double {
        if (to <= from) return 0.0
        val sorted = values.copyOfRange(from, to).also { it.sort() }
        val pos = (sorted.size - 1) * p / 100.0
        val lower = pos.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (pos - lower)
    }

    /**
     * 20 ms frame RMS of a 16 kHz 16-bit mono PCM stream, kept for the last [capacityFrames] frames.
     * Feed exactly the bytes given to the recognizer so frame indices match Vosk word times.
     */
    class FrameEnergyTracker(private val capacityFrames: Int = 400) {
        private val ring = DoubleArray(capacityFrames)
        private var framesTotal = 0L
        private var sumSquares = 0.0
        private var samplesInFrame = 0
        private var pendingLowByte = -1

        fun feed(data: ByteArray, length: Int) {
            var i = 0
            while (i < length) {
                val lowByte: Int
                val highByte: Int
                if (pendingLowByte >= 0) {
                    lowByte = pendingLowByte; highByte = data[i].toInt(); pendingLowByte = -1; i += 1
                } else if (i + 1 < length) {
                    lowByte = data[i].toInt() and 0xff; highByte = data[i + 1].toInt(); i += 2
                } else {
                    pendingLowByte = data[i].toInt() and 0xff; i += 1; continue
                }
                val sample = ((highByte shl 8) or lowByte).toShort().toDouble()
                sumSquares += sample * sample
                if (++samplesInFrame == FRAME_SAMPLES) {
                    ring[(framesTotal % capacityFrames).toInt()] = sqrt(sumSquares / FRAME_SAMPLES)
                    framesTotal++
                    sumSquares = 0.0
                    samplesInFrame = 0
                }
            }
        }

        /** Frames currently held, oldest first, and the absolute index of the first one. */
        fun snapshot(): Pair<DoubleArray, Long> {
            val count = minOf(framesTotal, capacityFrames.toLong()).toInt()
            val first = framesTotal - count
            return DoubleArray(count) { ring[((first + it) % capacityFrames).toInt()] } to first
        }

        fun evaluate(wordStartSec: Double, wordEndSec: Double, params: Params = DEFAULT): Result {
            val (frames, first) = snapshot()
            return RendyWakeIsolationCheck.evaluate(frames, first, wordStartSec, wordEndSec, params)
        }
    }
}
