package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.service.LiveTranscriptEvent.Capture
import com.hyundai.rentalhelper.service.LiveTranscriptEvent.Ended
import com.hyundai.rentalhelper.service.LiveTranscriptEvent.Final
import com.hyundai.rentalhelper.service.LiveTranscriptEvent.Partial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live STT subtitle line: what is shown for each real Pleos event sequence (no synthesized text). */
class LiveTranscriptStateTest {
    private fun LiveTranscriptState.shown(): String? = view()?.let {
        when {
            it.isPlaceholder -> "[placeholder]"
            it.isFinal -> "final:${it.text}"
            else -> "partial:${it.text}"
        }
    }

    @Test
    fun nothingBeforeCaptureThenPlaceholderThenPartialsThenFinal() {
        val s = LiveTranscriptState()
        assertNull("STT not ready: status keeps 마이크 준비 중, no line", s.view())
        s.apply(Capture(1))
        assertEquals("[placeholder]", s.shown())
        assertEquals(LiveTranscriptState.PLACEHOLDER, s.view()!!.text)
        // Real partial sequence shape (cumulative hypotheses that replace each other).
        for (p in listOf("스마트", "스마트 크루즈", "스마트 크루즈 어떻게")) {
            assertTrue(s.apply(Partial(1, p)))
            assertEquals("partial:$p", s.shown())
        }
        assertTrue(s.apply(Final(1, "스마트 크루즈 어떻게 켜?")))
        assertEquals("final:스마트 크루즈 어떻게 켜?", s.shown())
    }

    @Test
    fun finalStaysThroughAnswerAndIsReplacedOnlyByTheNextQuestion() {
        val s = LiveTranscriptState()
        s.apply(Capture(1)); s.apply(Partial(1, "스마트")); s.apply(Final(1, "스마트 크루즈 어떻게 켜"))
        assertFalse("session end after a final keeps the question", s.apply(Ended(1)))
        assertEquals("final:스마트 크루즈 어떻게 켜", s.shown()) // Gemini + TTS answer
        // Follow-up window: next STT session requested, not yet capturing -> previous question remains.
        assertEquals("final:스마트 크루즈 어떻게 켜", s.shown())
        s.apply(Capture(2))
        assertEquals("[placeholder]", s.shown())
        s.apply(Partial(2, "속도는"))
        assertEquals("partial:속도는", s.shown())
        s.apply(Final(2, "속도는 어떻게 바꿔"))
        assertEquals("final:속도는 어떻게 바꿔", s.shown())
    }

    @Test
    fun silenceOrErrorClearsTheLine() {
        val s = LiveTranscriptState()
        s.apply(Capture(1))
        assertTrue(s.apply(Ended(1)))
        assertNull("silence: no placeholder or text left", s.view())

        s.apply(Capture(2)); s.apply(Partial(2, "스마트 지"))
        assertTrue(s.apply(Ended(2)))
        assertNull("recognition failure without a final: the half sentence is not left on screen", s.view())
    }

    @Test
    fun lateAndStaleCallbacksAreIgnored() {
        val s = LiveTranscriptState()
        s.apply(Capture(2)); s.apply(Final(2, "뭐라고"))
        assertFalse("partial after the final", s.apply(Partial(2, "뭐")))
        assertFalse("duplicate final", s.apply(Final(2, "뭐라고요")))
        assertFalse("older session", s.apply(Partial(1, "그렇게")))
        assertFalse("older session end", s.apply(Ended(1)))
        assertFalse("blank partial", s.apply(Partial(2, "  ")))
        assertEquals("final:뭐라고", s.shown())
    }

    @Test
    fun aPartialWithoutAPrecedingCaptureStartsTheNewQuestion() {
        val s = LiveTranscriptState()
        s.apply(Capture(1)); s.apply(Final(1, "비상등 어디 있어"))
        s.apply(Partial(2, "그거"))
        assertEquals("partial:그거", s.shown())
    }

    @Test
    fun onlyReceivedTextIsEverShown() {
        val s = LiveTranscriptState()
        val received = mutableSetOf<String>()
        val events = listOf(Capture(1), Partial(1, "그래가"), Partial(1, "그래가지고 스마트"), Final(1, "그래가지고 스마트폰이 어떻게"),
            Ended(1), Capture(2), Partial(2, "적극"), Ended(2), Capture(3), Final(3, "뭐라고"))
        for (e in events) {
            if (e is Partial) received += e.text
            if (e is Final) received += e.text
            s.apply(e)
            s.view()?.takeUnless { it.isPlaceholder }?.let { assertTrue(it.text, it.text in received) }
        }
    }

    @Test
    fun resetClearsForANewInteraction() {
        val s = LiveTranscriptState()
        s.apply(Capture(1)); s.apply(Final(1, "스마트 크루즈"))
        s.reset()
        assertNull(s.view())
        s.apply(Capture(2))
        assertEquals("[placeholder]", s.shown())
    }
}
