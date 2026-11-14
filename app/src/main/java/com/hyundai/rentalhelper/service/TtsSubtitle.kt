package com.hyundai.rentalhelper.service

/**
 * TTS subtitle events, emitted by [VoiceAssistantEngine] from the Pleos playback callbacks
 * (EventListener.onStart = UpdateTtsStart, onDone = UpdateTtsDone, onStop/onError), tagged with the app utterance id.
 * [atMs] is SystemClock.elapsedRealtime() of the callback, so the UI can log how late it handled it.
 */
sealed class TtsSubtitleEvent {
    abstract val utteranceId: String
    abstract val atMs: Long

    data class Start(override val utteranceId: String, val text: String, override val atMs: Long) : TtsSubtitleEvent()

    /** Done, stopped, failed or interrupted by a newer QUEUE_FLUSH request. */
    data class End(override val utteranceId: String, val reason: String, override val atMs: Long) : TtsSubtitleEvent()
}

/**
 * What the TTS subtitle banner shows. The subtitle is the sentence that is playing NOW:
 *  - shown right before that utterance is handed to Pleos ([markShown], speak() waits until the frame is on screen)
 *    or, without that step, on its playback start; never at queue time (a queued briefing does not appear while the
 *    greeting is still playing);
 *  - a Start handled after its utterance already ended (main thread busy, callbacks posted late) is dropped, so a
 *    finished sentence never replaces or precedes the current one;
 *  - cleared when the shown utterance ends or is stopped; an End of another utterance does not touch it.
 * No delays are involved: ordering comes only from the playback callbacks and the utterance id.
 */
class TtsSubtitleState {
    sealed class Action {
        data class Show(val utteranceId: String, val text: String) : Action()
        data class Clear(val utteranceId: String) : Action()
        data class Ignore(val utteranceId: String, val reason: String) : Action()
    }

    var shownUtteranceId: String? = null
        private set

    /** [speakingNow] = the engine's utterance currently playing at the moment the event is handled. */
    fun onEvent(event: TtsSubtitleEvent, speakingNow: String?): Action = when (event) {
        is TtsSubtitleEvent.Start ->
            if (speakingNow != event.utteranceId) {
                Action.Ignore(event.utteranceId, "stale_start(playing=${speakingNow ?: "none"})")
            } else {
                shownUtteranceId = event.utteranceId
                Action.Show(event.utteranceId, event.text)
            }
        is TtsSubtitleEvent.End ->
            if (shownUtteranceId == event.utteranceId) {
                shownUtteranceId = null
                Action.Clear(event.utteranceId)
            } else {
                Action.Ignore(event.utteranceId, "end_of_not_shown(shown=${shownUtteranceId ?: "none"})")
            }
    }

    /**
     * The subtitle of [utteranceId] was put on screen right before its speak() call (so it is visible when the audio
     * starts). Its End (done, stop, failure, or cancellation before Pleos started) clears it.
     */
    fun markShown(utteranceId: String) {
        shownUtteranceId = utteranceId
    }

    fun reset() {
        shownUtteranceId = null
    }
}
