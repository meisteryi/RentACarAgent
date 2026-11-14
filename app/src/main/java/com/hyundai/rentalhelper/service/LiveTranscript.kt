package com.hyundai.rentalhelper.service

/**
 * Live STT transcript events, emitted by [VoiceAssistantEngine] only from real Pleos STT callbacks
 * (`ResultListener.onUpdated(stt, completed)`), tagged with the STT session they belong to. Nothing here is
 * synthesized: a partial is the recognizer's current hypothesis for the whole utterance (it replaces, not
 * appends), and [Final] carries the exact string that is handed to the question pipeline.
 */
sealed class LiveTranscriptEvent {
    abstract val sessionId: Long

    /** Microphone capture confirmed for this session (audio after the service's onReady). */
    data class Capture(override val sessionId: Long) : LiveTranscriptEvent()

    /** Interim hypothesis (`completed=false`). */
    data class Partial(override val sessionId: Long, val text: String) : LiveTranscriptEvent()

    /** Final recognition (`completed=true`), identical to what onSttTextReceived passes on. */
    data class Final(override val sessionId: Long, val text: String) : LiveTranscriptEvent()

    /** The session was finalized (final, silence, error or stop). */
    data class Ended(override val sessionId: Long) : LiveTranscriptEvent()
}

/** What the transcript line under the RENDY voice status shows. */
data class LiveTranscriptView(val text: String, val isFinal: Boolean, val isPlaceholder: Boolean)

/**
 * Transcript line state for one wake interaction.
 *  - Before capture is confirmed: nothing (the status keeps "마이크 준비 중").
 *  - Capture confirmed, no text yet: the placeholder "말씀해 주세요".
 *  - Partials replace the text; the final locks it (later partials of that session are ignored).
 *  - The last question stays visible while Gemini and the TTS answer run, and is replaced only when the next
 *    session's capture is confirmed (or its first partial arrives).
 *  - A session that ends without a final (silence, error) clears the line.
 *  - Callbacks of an older session are ignored.
 */
class LiveTranscriptState {
    var sessionId: Long = 0L
        private set
    private var text: String? = null
    private var isFinal = false
    private var listening = false

    /** Applies [event]; returns true when the visible line changed. */
    fun apply(event: LiveTranscriptEvent): Boolean {
        val before = view()
        val s = event.sessionId
        if (s <= 0L || s < sessionId) return false // stale session
        when (event) {
            is LiveTranscriptEvent.Capture -> {
                if (s > sessionId) startSession(s)
                if (text == null) listening = true
            }
            is LiveTranscriptEvent.Partial -> {
                val t = event.text.trim()
                if (t.isEmpty()) return false
                if (s > sessionId) startSession(s)
                if (isFinal) return false // the final is already shown for this session
                text = t
                listening = false
            }
            is LiveTranscriptEvent.Final -> {
                val t = event.text.trim()
                if (t.isEmpty()) return false
                if (s > sessionId) startSession(s)
                if (isFinal) return false // duplicate final
                text = t
                isFinal = true
                listening = false
            }
            is LiveTranscriptEvent.Ended -> {
                if (s != sessionId) return false
                listening = false
                if (!isFinal) text = null // nothing was recognized: no half sentence is left on screen
            }
        }
        return view() != before
    }

    fun reset() {
        text = null
        isFinal = false
        listening = false
    }

    fun view(): LiveTranscriptView? = when {
        text != null -> LiveTranscriptView(text!!, isFinal, isPlaceholder = false)
        listening -> LiveTranscriptView(PLACEHOLDER, isFinal = false, isPlaceholder = true)
        else -> null
    }

    private fun startSession(s: Long) {
        sessionId = s
        text = null
        isFinal = false
        listening = false
    }

    companion object {
        const val PLACEHOLDER = "말씀해 주세요"
    }
}
