package com.hyundai.rentalhelper.service

/**
 * Decides whether a Pleos STT session that was requested while the wake prompt was still playing
 * ("parallel" wake STT) may be kept.
 *
 * Pleos on-device STT (service 4.2.10) has no prepare-only call: request() builds the recognition engine and then
 * immediately creates and starts the service's own AudioRecord (ns:false, aec:false). onReady
 * (ReadyToStartRecognition) is sent between those two steps, so it is the last moment before recording starts.
 * If that moment comes while the prompt is still playing, or too soon after it ended, the session would record
 * the prompt itself and must be discarded.
 */
internal object SttPromptOverlapGuard {
    /** Minimum gap between prompt playback completion (TTS onDone) and onReady for a parallel session to be kept. */
    const val MIN_READY_AFTER_PROMPT_MS = 75L

    enum class Mode(val property: String) {
        SEQUENTIAL("sequential"),
        PARALLEL("parallel"),
        /** Debug diagnostics only: never discards, to observe whether prompt audio leaks into recognition. */
        PARALLEL_UNGUARDED("parallel_unguarded");

        companion object {
            fun from(value: String?): Mode = values().firstOrNull { it.property == value?.trim() } ?: SEQUENTIAL
        }
    }

    data class Decision(val keep: Boolean, val reason: String, val readyAfterPromptMs: Long)

    /**
     * @param promptBusy wake prompt TTS still playing or queued at onReady
     * @param promptDoneAtMs elapsedRealtime of prompt playback completion, 0 if unknown
     */
    fun onServiceReady(promptBusy: Boolean, promptDoneAtMs: Long, readyAtMs: Long, guarded: Boolean = true): Decision {
        val after = if (!promptBusy && promptDoneAtMs > 0L) readyAtMs - promptDoneAtMs else -1L
        if (!guarded) return Decision(true, "unguarded diagnostic mode", after)
        return when {
            promptBusy -> Decision(false, "service ready while the prompt is still playing", after)
            promptDoneAtMs <= 0L -> Decision(false, "prompt completion time unknown", after)
            after < MIN_READY_AFTER_PROMPT_MS -> Decision(false, "service ready ${after}ms after the prompt (< ${MIN_READY_AFTER_PROMPT_MS}ms)", after)
            else -> Decision(true, "service ready ${after}ms after the prompt", after)
        }
    }
}
