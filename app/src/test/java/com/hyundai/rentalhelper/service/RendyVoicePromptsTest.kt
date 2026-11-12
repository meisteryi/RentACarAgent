package com.hyundai.rentalhelper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the wake response: "렌디" -> TTS "네, 말씀하세요!" -> (playback ends) -> Pleos STT.
 * The ordering itself is exercised on a device/emulator with ACTION_SIMULATE_WAKE_WORD; these checks keep the
 * text and the TTS-before-STT guards from being changed silently.
 */
class RendyVoicePromptsTest {
    private val mainSrc = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val service = mainSrc.resolve("com/hyundai/rentalhelper/service/CoachOverlayService.kt").readText(Charsets.UTF_8)

    @Test
    fun wakeResponseIsSpeakNow() {
        assertEquals("네, 말씀하세요!", RendyVoicePrompts.WAKE_PROMPT_TEXT)
    }

    @Test
    fun wakeResponseIsNotAWaitOrProcessingMessage() {
        val wait = listOf(RendyVoicePrompts.PREPARING_STATUS, RendyVoicePrompts.ANSWER_LOOKUP_STATUS,
            RendyVoicePrompts.HEARING_STATUS, RendyVoicePrompts.ANSWERING_STATUS, "네, 잠시만요.")
        wait.forEach { assertNotEquals(it, RendyVoicePrompts.WAKE_PROMPT_TEXT) }
        assertFalse(RendyVoicePrompts.WAKE_PROMPT_TEXT.contains("잠시"))
    }

    @Test
    fun noSourceSpeaksTheOldWaitPhraseAsWakeResponse() {
        val offenders = mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("잠시만요") }.map { it.name }.toList()
        assertTrue("old wake phrase found in $offenders", offenders.isEmpty())
    }

    @Test
    fun serviceSpeaksTheConstantAndOpensSttOnlyAfterPlayback() {
        assertTrue(service.contains("WAKE_PROMPT_TEXT = RendyVoicePrompts.WAKE_PROMPT_TEXT"))
        val handler = service.substringAfter("private fun handleWakeDetected(").substringBefore("private fun startWakeSttAfterPrompt")
        assertTrue("wake handler must speak the wake prompt", handler.contains("text = WAKE_PROMPT_TEXT"))
        // The only STT start allowed in the handler is the guarded parallel request (SttPromptOverlapGuard),
        // and only after the prompt has been dispatched; never the plain sequential start.
        assertFalse("wake handler must not open STT directly", handler.contains("startPleosRecognition("))
        if (handler.contains("startPleosRecognitionDuringPrompt")) {
            assertTrue(handler.indexOf("voiceEngine.speak(") < handler.indexOf("startPleosRecognitionDuringPrompt"))
            assertTrue(handler.contains("guarded = sttMode == SttPromptOverlapGuard.Mode.PARALLEL"))
        }
        val engine = mainSrc.resolve("com/hyundai/rentalhelper/service/VoiceAssistantEngine.kt").readText(Charsets.UTF_8)
        val ready = engine.substringAfter("override fun onReady() {\n            if (isCurrentSession())").substringBefore("override fun onStartedRecognition")
        assertTrue("parallel sessions must pass the prompt-overlap guard at onReady",
            ready.contains("SttPromptOverlapGuard.onServiceReady") && ready.contains("discardPromptOverlapSession"))
        val start = service.substringAfter("private fun startWakeSttAfterPrompt() {").substringBefore("private fun offerWakeSttRetry")
        assertTrue("STT must wait while TTS is busy", start.contains("if (voiceEngine.isTtsBusy)"))
        assertTrue(start.contains("startPleosRecognition"))
        // STT is triggered from the TTS-finished callback.
        val ttsCallback = service.substringAfter("voiceEngine.onTtsPlaybackStateChanged = { isBusy ->").substringBefore("voiceEngine.onSpokenSubtitle")
        assertTrue(ttsCallback.contains("} else if (wakePromptPending) {") && ttsCallback.contains("startWakeSttAfterPrompt()"))
        // Both the real Vosk callback and the debug simulation use the same handler.
        assertTrue(service.contains("handleWakeDetected(detection.text"))
        assertTrue(service.contains("ACTION_SIMULATE_WAKE_WORD") && service.contains("BuildConfig.DEBUG"))
    }
}
