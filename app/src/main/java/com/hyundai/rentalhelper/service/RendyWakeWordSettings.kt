package com.hyundai.rentalhelper.service

/** Tunable MVP settings for offline Korean wake-word testing. */
object RendyWakeWordSettings {
    const val KEYWORD = "렌디"
    const val SAMPLE_RATE_HZ = 16_000

    // Vosk word confidence is useful for MVP tuning but is not a calibrated wake-word probability.
    const val MIN_CONFIDENCE = 0.70f
    // PCM RMS onset threshold used only to measure voice/silence timing; it does not accept/reject keywords.
    const val VOICE_RMS_THRESHOLD = 180.0

    // How long Vosk waits for silence before finalizing a short wake-word utterance.
    const val END_OF_SPEECH_SECONDS = 0.65f
}
