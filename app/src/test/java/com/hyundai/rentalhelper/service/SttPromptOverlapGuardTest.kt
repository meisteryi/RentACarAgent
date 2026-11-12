package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.service.SttPromptOverlapGuard.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttPromptOverlapGuardTest {

    @Test
    fun discardsWhenServiceIsReadyWhilePromptPlays() {
        val d = SttPromptOverlapGuard.onServiceReady(promptBusy = true, promptDoneAtMs = 0L, readyAtMs = 1_000L)
        assertFalse(d.keep)
    }

    @Test
    fun discardsWhenReadyTooSoonAfterPrompt() {
        val d = SttPromptOverlapGuard.onServiceReady(promptBusy = false, promptDoneAtMs = 1_000L,
            readyAtMs = 1_000L + SttPromptOverlapGuard.MIN_READY_AFTER_PROMPT_MS - 1)
        assertFalse(d.keep)
        assertEquals(SttPromptOverlapGuard.MIN_READY_AFTER_PROMPT_MS - 1, d.readyAfterPromptMs)
    }

    @Test
    fun keepsWhenReadyAfterGuard() {
        val d = SttPromptOverlapGuard.onServiceReady(promptBusy = false, promptDoneAtMs = 1_000L,
            readyAtMs = 1_000L + SttPromptOverlapGuard.MIN_READY_AFTER_PROMPT_MS)
        assertTrue(d.keep)
    }

    @Test
    fun discardsWhenPromptCompletionUnknown() {
        assertFalse(SttPromptOverlapGuard.onServiceReady(promptBusy = false, promptDoneAtMs = 0L, readyAtMs = 5_000L).keep)
    }

    @Test
    fun unguardedDiagnosticNeverDiscards() {
        assertTrue(SttPromptOverlapGuard.onServiceReady(promptBusy = true, promptDoneAtMs = 0L, readyAtMs = 1L, guarded = false).keep)
    }

    @Test
    fun modeParsingDefaultsToSequential() {
        assertEquals(Mode.SEQUENTIAL, Mode.from(null))
        assertEquals(Mode.SEQUENTIAL, Mode.from("bogus"))
        assertEquals(Mode.PARALLEL, Mode.from(" parallel "))
        assertEquals(Mode.PARALLEL_UNGUARDED, Mode.from("parallel_unguarded"))
    }
}
