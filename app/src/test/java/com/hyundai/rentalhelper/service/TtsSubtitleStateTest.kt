package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.service.TtsSubtitleEvent.End
import com.hyundai.rentalhelper.service.TtsSubtitleEvent.Start
import com.hyundai.rentalhelper.service.TtsSubtitleState.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TTS subtitle = the sentence playing now; replays the event orders seen on the emulator. */
class TtsSubtitleStateTest {
    private val intro = "안녕하세요! 렌터카 AI 비서 렌디예요."
    private val briefing = "탑승하신 더 뉴 아이오닉 5는 전기차로, …"

    @Test
    fun introThenBriefingInPlaybackOrder() {
        val s = TtsSubtitleState()
        // briefing was only REQUESTED here (queued): no event, nothing shown.
        assertEquals(Action.Show("rendy_intro", intro), s.onEvent(Start("rendy_intro", intro, 0), "rendy_intro"))
        assertEquals(Action.Clear("rendy_intro"), s.onEvent(End("rendy_intro", "done", 4077), null))
        assertEquals(Action.Show("utterance_briefing-1", briefing), s.onEvent(Start("utterance_briefing-1", briefing, 4218), "utterance_briefing-1"))
        assertEquals(Action.Clear("utterance_briefing-1"), s.onEvent(End("utterance_briefing-1", "done", 20800), null))
        assertNull(s.shownUtteranceId)
    }

    @Test
    fun lateIntroStartAfterTheBriefingBeganIsDropped() {
        // Before the fix (2026-11-14 22:13): the intro Start was handled ~3 s late, after the briefing had started,
        // and the intro sentence was on screen while the briefing was playing.
        val s = TtsSubtitleState()
        val a = s.onEvent(Start("rendy_intro", intro, 0), "utterance_briefing-1")
        assertTrue(a is Action.Ignore)
        assertTrue(s.onEvent(End("rendy_intro", "done", 4077), "utterance_briefing-1") is Action.Ignore)
        assertEquals(Action.Show("utterance_briefing-1", briefing), s.onEvent(Start("utterance_briefing-1", briefing, 4218), "utterance_briefing-1"))
    }

    @Test
    fun startHandledAfterItsOwnEndShowsNothing() {
        val s = TtsSubtitleState()
        assertTrue(s.onEvent(Start("utterance_qa-4", "답변", 0), null) is Action.Ignore)
        assertTrue(s.onEvent(End("utterance_qa-4", "done", 900), null) is Action.Ignore)
        assertNull(s.shownUtteranceId)
    }

    @Test
    fun shownBeforeSpeakThenStartIsIdempotentAndEndClears() {
        val s = TtsSubtitleState()
        s.markShown("rendy_wake_prompt-2") // on screen right before speak()
        assertEquals(Action.Show("rendy_wake_prompt-2", "네, 말씀하세요!"),
            s.onEvent(Start("rendy_wake_prompt-2", "네, 말씀하세요!", 0), "rendy_wake_prompt-2"))
        assertEquals(Action.Clear("rendy_wake_prompt-2"), s.onEvent(End("rendy_wake_prompt-2", "done", 1500), null))
    }

    @Test
    fun cancelledBeforePleosStartedStillClearsItsSubtitle() {
        val s = TtsSubtitleState()
        s.markShown("utterance_qa-7")
        // A newer QUEUE_FLUSH cancelled it before Pleos started: End without any Start.
        assertEquals(Action.Clear("utterance_qa-7"), s.onEvent(End("utterance_qa-7", "Interrupted by a newer QUEUE_FLUSH request", 10), null))
        assertNull(s.shownUtteranceId)
    }

    @Test
    fun stopOrFlushClearsOnlyTheShownSentence() {
        val s = TtsSubtitleState()
        s.onEvent(Start("utterance_qa-5", "첫 답변", 0), "utterance_qa-5")
        assertTrue("an older utterance's end does not clear", s.onEvent(End("utterance_qa-4", "done", 10), "utterance_qa-5") is Action.Ignore)
        assertEquals(Action.Clear("utterance_qa-5"), s.onEvent(End("utterance_qa-5", "Interrupted by a newer QUEUE_FLUSH request", 20), null))
        assertEquals(Action.Show("rendy_wake_prompt-6", "네, 말씀하세요!"),
            s.onEvent(Start("rendy_wake_prompt-6", "네, 말씀하세요!", 30), "rendy_wake_prompt-6"))
    }
}
