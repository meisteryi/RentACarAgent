package com.hyundai.rentalhelper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RendyWakeIsolationCheckTest {
    private val sr = 16_000

    /** 16-bit LE PCM: segments of (seconds, amplitude); amplitude 0 = background noise of +-40. */
    private fun pcm(vararg segments: Pair<Double, Int>): ByteArray {
        val samples = ArrayList<Short>()
        var phase = 0.0
        val rnd = java.util.Random(1)
        for ((sec, amp) in segments) repeat((sec * sr).toInt()) {
            phase += 2 * PI * 220 / sr
            val v = if (amp == 0) rnd.nextInt(81) - 40 else (amp * sin(phase)).toInt()
            samples.add(v.toShort())
        }
        return ByteArray(samples.size * 2).also { b ->
            samples.forEachIndexed { i, s -> b[2 * i] = (s.toInt() and 0xff).toByte(); b[2 * i + 1] = (s.toInt() shr 8).toByte() }
        }
    }

    private fun check(bytes: ByteArray, start: Double, end: Double, chunk: Int = 2048): RendyWakeIsolationCheck.Result {
        val t = RendyWakeIsolationCheck.FrameEnergyTracker(600)
        var i = 0
        while (i < bytes.size) { val n = minOf(chunk, bytes.size - i); t.feed(bytes.copyOfRange(i, i + n), n); i += n }
        return t.evaluate(start, end)
    }

    @Test
    fun isolatedWakeWordPasses() {
        val r = check(pcm(1.5 to 0, 0.5 to 3000, 1.0 to 0), 1.5, 2.0)
        assertTrue(r.reason, r.isolated)
        assertEquals(500.0, r.regionMs.toDouble(), 40.0)
    }

    @Test
    fun speechJustBeforeIsRejected() {
        val r = check(pcm(1.0 to 0, 1.0 to 3000, 0.2 to 0, 0.5 to 3000, 1.0 to 0), 2.2, 2.7)
        assertFalse(r.isolated)
        assertTrue(r.reason, r.preVoicedMs >= RendyWakeIsolationCheck.MAX_CONTEXT_VOICED_MS)
    }

    @Test
    fun speechJustAfterIsRejected() {
        val r = check(pcm(1.0 to 0, 0.5 to 3000, 0.2 to 0, 0.4 to 3000, 1.0 to 0), 1.0, 1.5)
        assertFalse(r.isolated)
        assertTrue(r.reason, r.postVoicedMs >= RendyWakeIsolationCheck.MAX_CONTEXT_VOICED_MS)
    }

    @Test
    fun naturalPauseAfterSpeechPasses() {
        val r = check(pcm(1.0 to 0, 1.0 to 3000, 0.8 to 0, 0.5 to 3000, 1.0 to 0), 2.8, 3.3)
        assertTrue(r.reason, r.isolated)
    }

    @Test
    fun shortPauseAfterSpeechIsRejectedByDefault() {
        assertEquals(600, RendyWakeIsolationCheck.WINDOW_MS)
        val r = check(pcm(1.0 to 0, 1.0 to 3000, 0.5 to 0, 0.5 to 3000, 1.0 to 0), 2.5, 3.0)
        assertFalse(r.isolated)
    }

    @Test
    fun longVoicedRegionIsRejected() {
        val r = check(pcm(1.0 to 0, 2.0 to 3000, 1.0 to 0), 1.5, 2.0)
        assertFalse(r.isolated)
        assertTrue(r.reason, r.regionMs > RendyWakeIsolationCheck.MAX_REGION_MS)
    }

    @Test
    fun slowWordAlignedLateStillPasses() {
        // Vosk aligned only the second half of a 1.2 s word: the region is grown from the audio, not the word times.
        val r = check(pcm(1.0 to 0, 1.2 to 3000, 1.0 to 0), 1.7, 2.2)
        assertTrue(r.reason, r.isolated)
        assertEquals(1200.0, r.regionMs.toDouble(), 60.0)
    }

    @Test
    fun trackerHandlesOddChunkBoundaries() {
        val bytes = pcm(0.5 to 0, 0.3 to 2000, 0.5 to 0)
        val a = check(bytes, 0.5, 0.8, chunk = 2048)
        val b = check(bytes, 0.5, 0.8, chunk = 333)
        assertEquals(a, b)
    }

    @Test
    fun percentileMatchesLinearInterpolation() {
        val v = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)
        assertEquals(1.8, RendyWakeIsolationCheck.percentile(v, 0, 5, 20.0), 1e-9)
        assertEquals(3.0, RendyWakeIsolationCheck.percentile(v, 0, 5, 50.0), 1e-9)
    }
}
