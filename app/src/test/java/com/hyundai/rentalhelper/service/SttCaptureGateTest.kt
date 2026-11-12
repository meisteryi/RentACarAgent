package com.hyundai.rentalhelper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Mic ready" must never be signalled from audio events that precede the service's ReadyToStartRecognition. */
class SttCaptureGateTest {
    @Test
    fun audioBeforeOnReadyIsNotCapture() {
        val g = SttCaptureGate()
        assertFalse(g.onAudioEvidence()) // e.g. stale RMS ~1 s before the recorder existed (measured)
        assertFalse(g.onAudioEvidence())
        assertEquals(2, g.ignoredBeforeReady)
        assertFalse(g.captureConfirmed)
        g.onReady()
        assertTrue(g.onAudioEvidence())
        assertTrue(g.captureConfirmed)
        assertFalse("confirmed only once", g.onAudioEvidence())
    }

    @Test
    fun onReadyAloneIsNotCapture() {
        val g = SttCaptureGate()
        g.onReady()
        assertFalse(g.captureConfirmed)
    }

    @Test
    fun engineSignalsListeningAndCueOnlyThroughTheGate() {
        val src = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/hyundai/rentalhelper/service/VoiceAssistantEngine.kt").readText(Charsets.UTF_8)
        val mark = src.substringAfter("private fun markCaptureReady(").substringBefore("override fun onReady()")
        assertTrue(mark.contains("captureGate.onAudioEvidence()"))
        // LISTENING state and the capture-ready callback (cue) come after the gate check.
        assertTrue(mark.indexOf("captureGate.onAudioEvidence()") < mark.indexOf("setSttState(SttState.LISTENING"))
        assertTrue(mark.indexOf("captureGate.onAudioEvidence()") < mark.indexOf("onSttCaptureReady?.invoke"))
        // onReady itself never marks capture.
        // The STT listener's onReady (the TTS EventListener earlier in the file also has an onReady).
        val onReady = src.substringAfter("private fun markCaptureReady(").substringAfter("override fun onReady() {")
            .substringBefore("override fun onStartedRecognition")
        assertFalse(onReady.contains("markCaptureReady"))
    }
}
