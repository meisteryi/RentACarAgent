package com.hyundai.rentalhelper.service

/**
 * Fixed RENDY voice/status texts, kept separate by purpose so a wait/processing message can never be used
 * as the wake response again (regression: the wake TTS had been replaced by a "please wait" phrase).
 * Covered by RendyVoicePromptsTest.
 */
object RendyVoicePrompts {
    /**
     * Spoken by TTS right after the wake word "렌디" is detected. Pleos STT is opened only after this playback
     * has finished and audio focus is released (CoachOverlayService.startWakeSttAfterPrompt), so RENDY never
     * captures its own voice.
     */
    const val WAKE_PROMPT_TEXT = "네, 말씀하세요!"

    /** On-screen status while the microphone is being prepared (not spoken). */
    const val PREPARING_STATUS = "마이크 준비 중"

    /** On-screen status once Pleos STT is actually listening (not spoken). */
    const val LISTENING_STATUS = "네, 말씀하세요!"

    /** On-screen status while speech is being received (not spoken). */
    const val HEARING_STATUS = "질문을 듣는 중"

    /** On-screen processing/wait status after the question was heard (not spoken). */
    const val ANSWER_LOOKUP_STATUS = "답변을 찾는 중"

    /** On-screen status while the answer is played. */
    const val ANSWERING_STATUS = "답변하는 중"

    /** Spoken once when the first STT attempt heard nothing. */
    const val RETRY_PROMPT_TEXT = "한 번 더 말씀해 주세요."
}
