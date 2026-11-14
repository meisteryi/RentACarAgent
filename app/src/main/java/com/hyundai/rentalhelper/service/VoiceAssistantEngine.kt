package com.hyundai.rentalhelper.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import ai.pleos.playground.stt.SpeechToText as PleosSpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttOnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.util.Log
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.intent.DrivingState
import com.hyundai.rentalhelper.intent.GeminiFlashProxyIntentClassifier
import com.hyundai.rentalhelper.intent.HttpUrlConnectionProxyTransport
import com.hyundai.rentalhelper.intent.RendyIntentFeatureFlags
import com.hyundai.rentalhelper.intent.RendyQuestionCoordinator
import com.hyundai.rentalhelper.intent.RendyQuestionPipeline
import com.hyundai.rentalhelper.intent.RuleBasedIntentClassifier
import com.hyundai.rentalhelper.intent.VoiceAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.ArrayDeque
import java.util.Locale

/** STT 입력과 차량 맞춤형 TTS 출력을 담당합니다. */
class VoiceAssistantEngine(
    private val context: Context,
    private val buttonDb: CarButtonDatabase
) {

    enum class SttState { IDLE, LISTENING, PROCESSING, RESULT, ERROR }

    companion object {
        private const val TAG = "RendyTTS"
        private const val STT_TAG = "RendySTT"
        private const val INTENT_TAG = "RendyIntent"
        private const val DIALOGUE_TAG = "RendyDialogue"
        const val RENDY_INTRO_TEXT = "안녕하세요! 렌터카 AI 비서 렌디예요."
        const val RENDY_INTRO_UTTERANCE_ID = "rendy_intro"
        private const val UTTERANCE_ID_BRIEFING = "utterance_briefing"
        private const val UTTERANCE_ID_QA = "utterance_qa"
        private const val STT_DIAGNOSTIC_INTERVAL_MS = 1_000L
        /** Measured service prep after the wake prompt reached 4.85 s once (cold); 8 s leaves margin. */
        const val STT_CAPTURE_TIMEOUT_MS = 8_000L
        const val STT_NO_SPEECH_TIMEOUT_MS = 10_000L
        private const val WATCHDOG_CAPTURE = "capture"
        private const val WATCHDOG_SPEECH = "speech"
    }

    private data class SpeechRequest(
        val text: String,
        val utteranceId: String,
        val queueMode: Int,
        val onCompleted: (() -> Unit)?,
        val onFailed: ((String) -> Unit)?,
        val requestedAtElapsedMs: Long,
        var startedAtElapsedMs: Long = 0L
    )

    private var tts: PleosTextToSpeech? = null
    private var isTtsReady = false
    private var ttsInitializationFailed = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var pleosStt: PleosSpeechToText? = null
    private var pleosSttInitialized = false
    private var pleosSttInitializationFailed = false
    private var pleosSttListener: ResultListener? = null
    private var sttSessionSequence = 0L
    private var activeSttSessionId = 0L
    private var sttSessionFinalizing = false
    private var sttSessionEnded = false
    private val sttHandler = Handler(Looper.getMainLooper())
    private var sttFinalizeRunnable: Runnable? = null
    private var pleosSttStartPending = false
    private var pleosSttRequestActive = false
    private var pleosSttManualStop = false
    private var pleosSttHasFinalResult = false
    private var sttInitializationStartedAtMs = 0L
    @Volatile private var sttStartRequestedAtMs = 0L
    @Volatile private var sttStartTrigger = "unknown"
    @Volatile private var activeSttRequestedAtMs = 0L
    @Volatile private var activeSttTrigger = "unknown"
    private var sttState = SttState.IDLE
    /** Parallel wake STT: the pending/active session was requested while the wake prompt was playing. */
    private var sttStartOverlapsPrompt = false
    private var sttStartOverlapGuarded = true
    @Volatile private var activeSttOverlapsPrompt = false
    private var activeSttOverlapGuarded = true
    /** elapsedRealtime when the last utterance finished playing and no speech remained (TTS onDone). */
    @Volatile private var lastSpeechDoneAtMs = 0L
    private var sttAudioFocusRequest: AudioFocusRequest? = null
    private var hasSttAudioFocus = false
    private val pendingSpeech = ArrayDeque<SpeechRequest>()
    private val activeSpeech = linkedMapOf<String, SpeechRequest>()
    private var utteranceSequence = 0L
    private val intentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val questionCoordinator = RendyQuestionCoordinator(intentScope)

    /** Vehicle motion for the guidance policy (set by the Service from VehicleSdkManager). UNKNOWN until then. */
    @Volatile var drivingStateProvider: () -> DrivingState = { DrivingState.UNKNOWN }

    /**
     * Called on the main thread for every answer just before it is spoken, so the Service can decide whether to
     * open the short follow-up listening window after playback.
     */
    var onAnswerDelivered: ((VoiceAnswer) -> Unit)? = null
    /** Built once so follow-up context survives between questions; null when LLM mode cannot run. */
    private val llmPipeline: RendyQuestionPipeline? by lazy {
        val endpoint = RendyIntentFeatureFlags.GEMINI_PROXY_ENDPOINT
        val knowledge = buttonDb.knowledge
        if (!RendyIntentFeatureFlags.ENABLE_LLM_CLASSIFIER || knowledge == null ||
            !GeminiFlashProxyIntentClassifier.isAllowedEndpoint(endpoint, RendyIntentFeatureFlags.ALLOW_LOCAL_HTTP_PROXY)
        ) return@lazy null
        Log.i(INTENT_TAG, "LLM intent routing enabled: db=${buttonDb.getDatabaseVersion()}, intents=${knowledge.intents.size}, " +
            "extraAllowlist=${RendyIntentFeatureFlags.VERIFIED_INTENT_IDS.size}")
        val gemini = GeminiFlashProxyIntentClassifier(
            endpoint = endpoint,
            minimumConfidence = RendyIntentFeatureFlags.MINIMUM_MODEL_CONFIDENCE,
            totalTimeoutMs = RendyIntentFeatureFlags.PROXY_TIMEOUT_MS,
            transport = HttpUrlConnectionProxyTransport(authToken = RendyIntentFeatureFlags.PROXY_TOKEN),
            allowLocalHttp = RendyIntentFeatureFlags.ALLOW_LOCAL_HTTP_PROXY
        )
        RendyQuestionPipeline(
            classifier = gemini,
            fallbackClassifier = RuleBasedIntentClassifier(knowledge.intents),
            knowledge = knowledge,
            contextWindowMs = RendyIntentFeatureFlags.CONTEXT_WINDOW_MS,
            clock = SystemClock::elapsedRealtime,
            restrictToIntents = RendyIntentFeatureFlags.VERIFIED_INTENT_IDS,
            drivingState = { drivingStateProvider() },
            // Follow-up turns: one /v1/rendy/turn call (dialogue act + intent) with the conversation state.
            turnClassifier = gemini
        )
    }

    /** Flag OFF (default) or no valid proxy: keyword rules with the same verification policy and follow-ups. */
    private val rulePipeline: RendyQuestionPipeline? by lazy {
        val knowledge = buttonDb.knowledge ?: return@lazy null
        val rules = RuleBasedIntentClassifier(knowledge.intents)
        RendyQuestionPipeline(
            classifier = rules,
            fallbackClassifier = rules,
            knowledge = knowledge,
            contextWindowMs = RendyIntentFeatureFlags.CONTEXT_WINDOW_MS,
            clock = SystemClock::elapsedRealtime,
            restrictToIntents = RendyIntentFeatureFlags.VERIFIED_INTENT_IDS,
            drivingState = { drivingStateProvider() }
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // 상태 리스너
    var onListeningStateChanged: ((isListening: Boolean) -> Unit)? = null
    var onSpeechResultReceived: ((query: String, title: String, answer: String) -> Unit)? = null
    var onErrorOccurred: ((errorMessage: String) -> Unit)? = null
    var onSpokenSubtitle: ((spokenText: String) -> Unit)? = null
    var onSttStateChanged: ((state: SttState, message: String?) -> Unit)? = null
    var onTtsPlaybackStateChanged: ((isBusy: Boolean) -> Unit)? = null
    /** Called once per STT session when real microphone capture is confirmed (audio after onReady). */
    var onSttCaptureReady: ((trigger: String) -> Unit)? = null
    /**
     * Parallel wake STT only: the early session was discarded before it could record the prompt
     * (see [SttPromptOverlapGuard]); the caller should fall back to a sequential request after the prompt.
     */
    var onPromptOverlapSttDiscarded: ((reason: String) -> Unit)? = null
    /** Final raw transcript hook for a later Q&A layer; this callback does not trigger answers. */
    var onSttTextReceived: ((query: String) -> Unit)? = null
    /**
     * Live transcript for the on-screen subtitle: capture, Pleos partial/final results and session end, tagged
     * with the STT session. Only real recognizer output is forwarded; the [LiveTranscriptEvent.Final] text is the
     * same string passed to [onSttTextReceived]. Called on the SDK callback thread.
     */
    var onLiveTranscript: ((LiveTranscriptEvent) -> Unit)? = null

    val isTtsBusy: Boolean
        get() = activeSpeech.isNotEmpty() || pendingSpeech.isNotEmpty()

    val isSttBusy: Boolean
        get() = pleosSttRequestActive || pleosSttStartPending || sttSessionFinalizing

    private var sdkInitialized = false
    private var sdkReadyForSpeech = false
    private var koreanLocaleConfigured = false
    private var ignoreNextStopEvent = false
    private var sdkEventListenerRegistered = false

    private val pleosEventListener = object : EventListener {
        override fun onReady() {
            sdkReadyForSpeech = true
            Log.i(TAG, "Pleos TTS event: ready")
            updateTtsReadiness()
        }

        override fun onStart() {
            val (id, request) = activeSpeech.entries.firstOrNull()?.let { it.key to it.value }
                ?: ("unknown" to null)
            val startedAt = SystemClock.elapsedRealtime()
            request?.startedAtElapsedMs = startedAt
            Log.i(TAG, "Pleos TTS utterance started: id=$id, requestToStartMs=${request?.let { startedAt - it.requestedAtElapsedMs } ?: -1L}")
            request?.let { onSpokenSubtitle?.invoke(it.text) }
        }

        override fun onDone() {
            val (id, request) = activeSpeech.entries.firstOrNull()?.let { it.key to it.value } ?: (null to null)
            val elapsed = request?.startedAtElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: -1L
            Log.i(TAG, "Pleos TTS utterance completed: id=${id ?: "unknown"}, playbackMs=$elapsed")
            finishUtterance(id, null)
        }

        override fun onStop() {
            if (ignoreNextStopEvent) {
                ignoreNextStopEvent = false
                Log.i(TAG, "Pleos TTS stop event consumed by queue flush")
                return
            }
            val id = activeSpeech.keys.firstOrNull()
            Log.w(TAG, "Pleos TTS utterance stopped: id=${id ?: "unknown"}")
            finishUtterance(id, "Pleos TTS playback stopped")
        }

        override fun onError(errorType: ErrorType) {
            val id = activeSpeech.keys.firstOrNull()
            Log.e(TAG, "Pleos TTS utterance error: id=${id ?: "unknown"}, error=$errorType")
            finishUtterance(id, "Pleos TTS error: $errorType")
        }

        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun createPleosSttListener(sessionId: Long) = object : ResultListener {
        private fun isCurrentSession(): Boolean = sessionId == activeSttSessionId
        private var captureMarkedReady = false
        private var sessionReadyAtMs = 0L
        private var sessionCaptureAtMs = 0L
        private var firstPartialAtMs = 0L
        private var partialCount = 0
        private var lastPartialAtMs = 0L
        private var sessionEndedAtMs = 0L

        private val captureGate = SttCaptureGate()

        /**
         * Capture is confirmed only by audio evidence that arrives AFTER the service's ReadyToStartRecognition
         * (onReady) for this session. SDK bytecode: onReady = service event "ReadyToStartRecognition", sent before
         * the service creates its AudioRecord; onUpdatedRms = "UpdateRms" (audio frames); onStartedRecognition =
         * "UpdateEpdState" EPD_ON (speech start). An RMS seen before onReady (measured once, ~1 s before the
         * recorder existed) is not trusted.
         */
        private fun markCaptureReady(source: String) {
            if (!isCurrentSession() || captureMarkedReady) return
            if (!captureGate.onAudioEvidence()) {
                if (!captureGate.readySeen && captureGate.ignoredBeforeReady == 1) {
                    Log.w(STT_TAG, "session=$sessionId $source before onReady ignored (not proof of capture)")
                }
                return
            }
            captureMarkedReady = true
            pleosSttRequestActive = true
            sttSessionEnded = false
            val now = SystemClock.elapsedRealtime()
            sessionCaptureAtMs = now
            setSttState(SttState.LISTENING, "Listening")
            onListeningStateChanged?.invoke(true)
            Log.i(STT_TAG, "session=$sessionId microphone capture confirmed by $source: trigger=$activeSttTrigger, requestToCaptureMs=${now - activeSttRequestedAtMs}, readyToCaptureMs=${if (sessionReadyAtMs > 0L) now - sessionReadyAtMs else -1L}")
            onLiveTranscript?.invoke(LiveTranscriptEvent.Capture(sessionId))
            scheduleSttWatchdog(sessionId, WATCHDOG_SPEECH, STT_NO_SPEECH_TIMEOUT_MS)
            onSttCaptureReady?.invoke(activeSttTrigger)
        }

        override fun onReady() {
            if (isCurrentSession()) {
                val readyAt = SystemClock.elapsedRealtime()
                if (activeSttOverlapsPrompt) {
                    val decision = SttPromptOverlapGuard.onServiceReady(isTtsBusy, lastSpeechDoneAtMs, readyAt, activeSttOverlapGuarded)
                    Log.i(STT_TAG, "session=$sessionId parallel STT ready: keep=${decision.keep}, reason=${decision.reason}, requestToReadyMs=${readyAt - activeSttRequestedAtMs}, readyAfterPromptMs=${decision.readyAfterPromptMs}")
                    if (!decision.keep) {
                        discardPromptOverlapSession(sessionId, decision.reason)
                        return
                    }
                }
                captureGate.onReady()
                sessionReadyAtMs = readyAt
                Log.i(STT_TAG, "session=$sessionId Pleos STT ready: trigger=$activeSttTrigger, requestToReadyMs=${readyAt - activeSttRequestedAtMs}")
            }
        }

        override fun onStartedRecognition() {
            // SDK: EPD_ON = the endpoint detector heard speech start (not "recognition started").
            if (isCurrentSession()) Log.i(STT_TAG, "session=$sessionId speech start (EPD_ON): requestToSpeechStartMs=${SystemClock.elapsedRealtime() - activeSttRequestedAtMs}")
            markCaptureReady("onStartedRecognition")
            if (isCurrentSession()) cancelSttWatchdog()
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            if (!isCurrentSession()) {
                Log.d(STT_TAG, "Ignoring stale callback for session=$sessionId")
                return
            }
            if (completed) {
                if (stt.isNotBlank() && !pleosSttHasFinalResult) {
                    pleosSttHasFinalResult = true
                    sttFinalizeRunnable?.let(sttHandler::removeCallbacks)
                    sttFinalizeRunnable = null
                    val finalAtMs = SystemClock.elapsedRealtime()
                    Log.i(STT_TAG, "session=$sessionId final recognition: trigger=$activeSttTrigger, text=$stt, requestToFinalMs=${finalAtMs - activeSttRequestedAtMs}, readyToFinalMs=${if (sessionReadyAtMs > 0L) finalAtMs - sessionReadyAtMs else -1L}, captureToFinalMs=${if (sessionCaptureAtMs > 0L) finalAtMs - sessionCaptureAtMs else -1L}, finalAfterEndMs=${if (sessionEndedAtMs > 0L) finalAtMs - sessionEndedAtMs else -1L}, partialCount=$partialCount")
                    onLiveTranscript?.invoke(LiveTranscriptEvent.Final(sessionId, stt))
                    onSttTextReceived?.invoke(stt)
                    setSttState(SttState.RESULT, "Text recognized")
                    if (sttSessionEnded) scheduleSttFinalization(sessionId, 250L)
                } else if (pleosSttHasFinalResult) {
                    Log.w(STT_TAG, "session=$sessionId duplicate final result ignored")
                } else {
                    Log.w(STT_TAG, "session=$sessionId final recognition callback contained empty text")
                }
            } else if (stt.isNotBlank()) {
                markCaptureReady("non-empty partial result")
                cancelSttWatchdog()
                val partialAtMs = SystemClock.elapsedRealtime()
                val first = firstPartialAtMs == 0L
                if (first) firstPartialAtMs = partialAtMs
                partialCount++
                val intervalMs = if (lastPartialAtMs > 0L) partialAtMs - lastPartialAtMs else -1L
                lastPartialAtMs = partialAtMs
                Log.i(STT_TAG, "session=$sessionId partial recognition: trigger=$activeSttTrigger, text=$stt, first=$first, requestToPartialMs=${partialAtMs - activeSttRequestedAtMs}, readyToPartialMs=${if (sessionReadyAtMs > 0L) partialAtMs - sessionReadyAtMs else -1L}, captureToPartialMs=${if (sessionCaptureAtMs > 0L) partialAtMs - sessionCaptureAtMs else -1L}, intervalMs=$intervalMs")
                onLiveTranscript?.invoke(LiveTranscriptEvent.Partial(sessionId, stt))
                setSttState(SttState.PROCESSING, "Processing speech")
            }
        }

        private var lastRmsLogAtMs = 0L
        private var lastEpdLogAtMs = 0L

        override fun onUpdatedEpdData(on: Long, off: Long) {
            val now = SystemClock.elapsedRealtime()
            if (isCurrentSession() && now - lastEpdLogAtMs >= STT_DIAGNOSTIC_INTERVAL_MS) {
                Log.d(STT_TAG, "session=$sessionId endpointData: on=$on, off=$off")
                lastEpdLogAtMs = now
            }
        }

        override fun onUpdatedRms(rms: Double) {
            val now = SystemClock.elapsedRealtime()
            markCaptureReady("first RMS callback")
            if (isCurrentSession() && now - lastRmsLogAtMs >= STT_DIAGNOSTIC_INTERVAL_MS) {
                Log.i(STT_TAG, "session=$sessionId inputRms=$rms")
                lastRmsLogAtMs = now
            }
        }

        override fun onEndedRecognition() {
            if (!isCurrentSession()) {
                Log.d(STT_TAG, "Ignoring stale end callback for session=$sessionId")
                return
            }
            sttSessionEnded = true
            sessionEndedAtMs = SystemClock.elapsedRealtime()
            pleosSttRequestActive = false
            releaseSttAudioFocus()
            onListeningStateChanged?.invoke(false)
            Log.i(STT_TAG, "session=$sessionId recognition ended: trigger=$activeSttTrigger, requestElapsedMs=${SystemClock.elapsedRealtime() - activeSttRequestedAtMs}, hasFinal=${pleosSttHasFinalResult}")
            when {
                pleosSttHasFinalResult -> {
                    setSttState(SttState.RESULT, "Text recognized")
                    scheduleSttFinalization(sessionId, 250L)
                }
                pleosSttManualStop -> finalizeSttSession(sessionId, SttState.IDLE, "Listening stopped")
                else -> {
                    Log.i(STT_TAG, "session=$sessionId ended; waiting 800ms for a possible final result callback")
                    scheduleSttFinalization(sessionId, 800L, SttState.ERROR, "No speech or recognition timed out")
                }
            }
            pleosSttManualStop = false
        }

        override fun onError(errorType: SttErrorType) {
            if (!isCurrentSession()) {
                Log.d(STT_TAG, "Ignoring stale error callback for session=$sessionId")
                return
            }
            if (pleosSttHasFinalResult) {
                Log.w(STT_TAG, "session=$sessionId ignored error after final result: $errorType")
                scheduleSttFinalization(sessionId, 250L)
                return
            }
            pleosSttRequestActive = false
            sttSessionEnded = true
            releaseSttAudioFocus()
            onListeningStateChanged?.invoke(false)
            val message = sttErrorMessage(errorType)
            Log.e(STT_TAG, "session=$sessionId Pleos STT error: code=$errorType; message=$message; requestElapsedMs=${SystemClock.elapsedRealtime() - activeSttRequestedAtMs}")
            if (!pleosSttManualStop) scheduleSttFinalization(sessionId, 800L, SttState.ERROR, message)
            else finalizeSttSession(sessionId, SttState.IDLE, "Listening stopped")
            pleosSttManualStop = false
        }
    }

    init {
        initTtsEngine()
        initSpeechRecognizer()
        initPleosStt()
    }

    private fun initTtsEngine() {
        try {
            val engine = PleosTextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            tts = engine
            Log.i(
                TAG,
                "Pleos TTS SDK selected: mode=ON_DEVICE, providerPackage=ai.pleos.playground.caas"
            )
            Log.i(TAG, "Pleos SDK v3.3.1 exposes ko-KR; requesting Locale.KOREA")
            Log.i(TAG, "Pleos SDK has no public speech-rate or pitch setter; rate=0.95/pitch=1.0 unavailable")
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try {
                        engine.addEventListener(pleosEventListener)
                        sdkEventListenerRegistered = true
                        engine.setAudioAttributes(speechAudioAttributes)
                        engine.setLocale(Locale.KOREA)
                        sdkInitialized = true
                        koreanLocaleConfigured = true
                        ttsInitializationFailed = false
                        Log.i(
                            TAG,
                            "Pleos TTS initialization succeeded; setLocale(Locale.KOREA) invoked " +
                                "(SDK API returns no status); ko-KR is documented as supported"
                        )
                        Log.i(TAG, "ON_DEVICE mode is initialized; no server-connection callback is required")
                        Log.i(
                            TAG,
                            "Pleos TTS AudioAttributes configured: " +
                                "usage=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, " +
                                "contentType=CONTENT_TYPE_SPEECH"
                        )
                        updateTtsReadiness()
                    } catch (e: Exception) {
                        failTtsInitialization("TTS configuration failed: ${e.message}", e)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    failTtsInitialization("Pleos TTS initialization failed: $errorType")
                }
            })
        } catch (e: Exception) {
            failTtsInitialization("Pleos TTS SDK creation failed: ${e.message}", e)
        }
    }

    private fun updateTtsReadiness() {
        isTtsReady = sdkInitialized && koreanLocaleConfigured
        if (isTtsReady) {
            Log.i(TAG, "Pleos Korean TTS ready for ko-KR speech")
            dispatchNextPendingSpeech()
        }
    }

    private fun failTtsInitialization(message: String, error: Throwable? = null) {
        ttsInitializationFailed = true
        isTtsReady = false
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        failPendingSpeech(message)
        onErrorOccurred?.invoke(message)
    }

    private fun initPleosStt() {
        try {
            sttInitializationStartedAtMs = SystemClock.elapsedRealtime()
            val engine = PleosSpeechToText(context.applicationContext, SttMode.ON_DEVICE)
            pleosStt = engine
            Log.i(STT_TAG, "Pleos SpeechToText SDK selected: mode=ON_DEVICE, SDK=3.3.1")
            Log.i(STT_TAG, "Pleos SDK documents ko-KR support; requesting Locale.KOREA")
            engine.initialize(object : SttOnInitializeListener {
                override fun onSuccess() {
                    try {
                        engine.setLocale(Locale.KOREA)
                        pleosSttInitialized = true
                        pleosSttInitializationFailed = false
                        Log.i(
                            STT_TAG,
                            "Pleos STT initialization succeeded; setLocale(Locale.KOREA) invoked " +
                                "(SDK API returns no status); ko-KR is documented as supported; " +
                                "initializationMs=${SystemClock.elapsedRealtime() - sttInitializationStartedAtMs}"
                        )
                        Log.i(STT_TAG, "ON_DEVICE mode requires no network or app registration")
                        if (pleosSttStartPending) beginPleosRecognition()
                    } catch (e: Exception) {
                        failPleosSttInitialization("Pleos STT configuration failed: ${e.message}", e)
                    }
                }

                override fun onFailure(errorType: SttErrorType) {
                    failPleosSttInitialization("Pleos STT initialization failed: $errorType")
                }
            })
        } catch (e: Exception) {
            failPleosSttInitialization("Pleos STT SDK creation failed: ${e.message}", e)
        }
    }

    private fun failPleosSttInitialization(message: String, error: Throwable? = null) {
        pleosSttInitializationFailed = true
        pleosSttInitialized = false
        pleosSttStartPending = false
        if (error == null) Log.e(STT_TAG, message) else Log.e(STT_TAG, message, error)
        setSttState(SttState.ERROR, message)
    }

    fun togglePleosRecognition() {
        if (pleosSttRequestActive || pleosSttStartPending) {
            stopPleosRecognition()
        } else {
            startPleosRecognition()
        }
    }

    /**
     * Parallel wake STT: request Pleos STT while the wake prompt is playing so the service's engine setup overlaps
     * the prompt. The STT audio focus is taken only when the prompt ends (taking it earlier would make the prompt's
     * own focus listener stop playback), and the session is discarded if the service becomes ready to record
     * before the prompt has finished ([SttPromptOverlapGuard]).
     */
    fun startPleosRecognitionDuringPrompt(trigger: String, guarded: Boolean = true) {
        startPleosRecognition(trigger, overlapPrompt = true, overlapGuarded = guarded)
    }

    fun startPleosRecognition(trigger: String = "PTT") = startPleosRecognition(trigger, overlapPrompt = false, overlapGuarded = true)

    private fun startPleosRecognition(trigger: String, overlapPrompt: Boolean, overlapGuarded: Boolean) {
        if (pleosSttRequestActive || pleosSttStartPending || sttSessionFinalizing) {
            Log.w(STT_TAG, "Duplicate STT start ignored; active=$pleosSttRequestActive, pending=$pleosSttStartPending, finalizing=$sttSessionFinalizing")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(STT_TAG, "RECORD_AUDIO permission missing; request through Android permission UI")
            setSttState(SttState.ERROR, "Microphone permission required")
            return
        }
        if (!overlapPrompt && (activeSpeech.isNotEmpty() || pendingSpeech.isNotEmpty())) {
            Log.w(STT_TAG, "PTT blocked while TTS is active or queued")
            setSttState(SttState.ERROR, "Wait for speech playback to finish")
            return
        }
        if (pleosSttInitializationFailed || pleosStt == null) {
            setSttState(SttState.ERROR, "Pleos STT is unavailable")
            return
        }
        cancelPendingQuestion("new_stt_session")
        pleosSttManualStop = false
        pleosSttHasFinalResult = false
        sttStartRequestedAtMs = SystemClock.elapsedRealtime()
        sttStartTrigger = trigger
        sttStartOverlapsPrompt = overlapPrompt
        sttStartOverlapGuarded = overlapGuarded
        pleosSttStartPending = true
        setSttState(SttState.PROCESSING, "Preparing microphone")
        Log.i(STT_TAG, "STT pipeline: trigger=$trigger, requestedAt=$sttStartRequestedAtMs, overlapsPrompt=$overlapPrompt, guarded=$overlapGuarded")
        Log.i(STT_TAG, "STT readiness baseline: sdkInitialized=$pleosSttInitialized, appScopedEngine=true")
        if (pleosSttInitialized) beginPleosRecognition()
    }

    private fun beginPleosRecognition() {
        val engine = pleosStt ?: return
        if (!pleosSttStartPending) return
        if (pleosSttRequestActive || sttSessionFinalizing) {
            pleosSttStartPending = false
            Log.w(STT_TAG, "Duplicate Pleos request prevented")
            return
        }
        val overlap = sttStartOverlapsPrompt
        val promptBusy = activeSpeech.isNotEmpty() || pendingSpeech.isNotEmpty()
        if (promptBusy && !overlap) {
            pleosSttStartPending = false
            setSttState(SttState.ERROR, "Wait for speech playback to finish")
            return
        }
        val focusRequestStartedAt = SystemClock.elapsedRealtime()
        // Parallel wake STT defers the STT focus to prompt completion (see startPleosRecognitionDuringPrompt).
        if (!(overlap && promptBusy) && !requestSttAudioFocus()) {
            pleosSttStartPending = false
            setSttState(SttState.ERROR, "STT audio focus unavailable")
            return
        }
        val focusGrantedAt = SystemClock.elapsedRealtime()
        try {
            pleosSttStartPending = false
            pleosSttRequestActive = true
            sttSessionSequence += 1
            activeSttSessionId = sttSessionSequence
            activeSttRequestedAtMs = sttStartRequestedAtMs.takeIf { it > 0L } ?: focusRequestStartedAt
            activeSttTrigger = sttStartTrigger
            activeSttOverlapsPrompt = overlap
            activeSttOverlapGuarded = sttStartOverlapGuarded
            sttSessionFinalizing = true
            sttSessionEnded = false
            val listener = createPleosSttListener(activeSttSessionId)
            pleosSttListener = listener
            engine.addListener(listener)
            Log.i(STT_TAG, "session=$activeSttSessionId pipeline: trigger=$activeSttTrigger, requestToAudioFocusMs=${focusGrantedAt - activeSttRequestedAtMs}, audioFocusCallMs=${focusGrantedAt - focusRequestStartedAt}, requestCallAt=${SystemClock.elapsedRealtime()}")
            engine.request()
            Log.i(STT_TAG, "session=$activeSttSessionId Pleos SpeechToText.request() returned: callElapsedMs=${SystemClock.elapsedRealtime() - focusGrantedAt}")
            scheduleSttWatchdog(activeSttSessionId, WATCHDOG_CAPTURE, STT_CAPTURE_TIMEOUT_MS)
        } catch (e: Exception) {
            pleosSttRequestActive = false
            pleosSttListener?.let { listener ->
                try { engine.removeListener(listener) } catch (_: Exception) {}
            }
            pleosSttListener = null
            sttSessionFinalizing = false
            releaseSttAudioFocus()
            Log.e(STT_TAG, "Pleos SpeechToText.request() failed", e)
            setSttState(SttState.ERROR, "STT request failed: ${e.message}")
        }
    }

    /**
     * Debug builds only (CoachOverlayService ACTION_DEBUG_STT_RESULT): deliver [text] as the final result of the
     * active, capture-confirmed Pleos STT session and stop it, exactly like a real final result. Used to drive the
     * real wake / follow-up window flow on an emulator without a usable microphone. NOT acoustic recognition.
     */
    fun debugDeliverSttResult(text: String): Boolean {
        if (!com.hyundai.rentalhelper.BuildConfig.DEBUG) return false
        if (!pleosSttRequestActive || activeSttSessionId == 0L || pleosSttHasFinalResult || sttState != SttState.LISTENING) {
            Log.w(STT_TAG, "DEBUG transcript injection ignored: no capture-confirmed STT session (state=$sttState)")
            return false
        }
        pleosSttHasFinalResult = true
        Log.i(STT_TAG, "session=$activeSttSessionId DEBUG injected final result (text injection, not acoustic): trigger=$activeSttTrigger")
        onLiveTranscript?.invoke(LiveTranscriptEvent.Final(activeSttSessionId, text))
        onSttTextReceived?.invoke(text)
        setSttState(SttState.RESULT, "Text recognized")
        stopPleosRecognition()
        return true
    }

    fun stopPleosRecognition() {
        val engine = pleosStt
        if (!pleosSttRequestActive && !pleosSttStartPending) return
        pleosSttManualStop = true
        pleosSttStartPending = false
        try {
            engine?.stop()
            Log.i(STT_TAG, "Pleos SpeechToText.stop() requested; microphone released")
        } catch (e: Exception) {
            Log.e(STT_TAG, "Pleos SpeechToText.stop() failed", e)
        }
        pleosSttRequestActive = false
        releaseSttAudioFocus()
        onListeningStateChanged?.invoke(false)
        finalizeSttSession(
            activeSttSessionId,
            if (pleosSttHasFinalResult) SttState.RESULT else SttState.IDLE,
            if (pleosSttHasFinalResult) "Text recognized" else "Listening stopped"
        )
    }

    /**
     * Parallel wake STT: stop a session that would record the prompt. Finalized without publishing an IDLE/ERROR
     * state (the wake UX would read that as "no speech"); the caller restarts STT sequentially.
     */
    private fun discardPromptOverlapSession(sessionId: Long, reason: String) {
        if (sessionId != activeSttSessionId) return
        pleosSttManualStop = true
        try {
            pleosStt?.stop()
        } catch (e: Exception) {
            Log.w(STT_TAG, "session=$sessionId stop of discarded parallel session failed: ${e.message}")
        }
        pleosSttRequestActive = false
        releaseSttAudioFocus()
        finalizeSttSession(sessionId, SttState.PROCESSING, "Preparing microphone", publishState = false)
        pleosSttManualStop = false
        Log.w(STT_TAG, "session=$sessionId parallel STT discarded before recording: $reason")
        onPromptOverlapSttDiscarded?.invoke(reason)
    }

    private fun scheduleSttFinalization(
        sessionId: Long,
        delayMs: Long,
        fallbackState: SttState? = null,
        fallbackMessage: String? = null
    ) {
        if (sessionId != activeSttSessionId) return
        sttFinalizeRunnable?.let(sttHandler::removeCallbacks)
        sttFinalizeRunnable = Runnable {
            if (sessionId != activeSttSessionId) return@Runnable
            val state = if (pleosSttHasFinalResult) SttState.RESULT else fallbackState ?: SttState.IDLE
            val message = if (pleosSttHasFinalResult) "Text recognized" else fallbackMessage ?: "Listening stopped"
            finalizeSttSession(sessionId, state, message)
        }.also { sttHandler.postDelayed(it, delayMs) }
    }

    private var sttWatchdogRunnable: Runnable? = null

    /**
     * Safety net, not a latency change: if the speech service never confirms capture (no audio after onReady)
     * or no speech starts after capture, end the session with an error instead of showing "마이크 준비 중"/listening
     * forever. Pleos' own endpoint detector normally ends sessions well before these limits.
     */
    private fun scheduleSttWatchdog(sessionId: Long, phase: String, timeoutMs: Long) {
        cancelSttWatchdog()
        val r = Runnable {
            if (sessionId != activeSttSessionId) return@Runnable
            sttWatchdogRunnable = null
            Log.w(STT_TAG, "session=$sessionId $phase timeout after ${timeoutMs}ms; stopping Pleos STT (trigger=$activeSttTrigger)")
            pleosSttManualStop = true
            try { pleosStt?.stop() } catch (e: Exception) { Log.w(STT_TAG, "Pleos STT stop on timeout failed: ${e.message}") }
            pleosSttRequestActive = false
            releaseSttAudioFocus()
            onListeningStateChanged?.invoke(false)
            finalizeSttSession(sessionId, SttState.ERROR,
                if (phase == WATCHDOG_CAPTURE) "Microphone did not become ready" else "No speech or recognition timed out")
        }
        sttWatchdogRunnable = r
        sttHandler.postDelayed(r, timeoutMs)
    }

    private fun cancelSttWatchdog() {
        sttWatchdogRunnable?.let(sttHandler::removeCallbacks)
        sttWatchdogRunnable = null
    }

    private fun finalizeSttSession(sessionId: Long, state: SttState, message: String, publishState: Boolean = true) {
        if (sessionId != activeSttSessionId) return
        activeSttOverlapsPrompt = false
        cancelSttWatchdog()
        sttFinalizeRunnable?.let(sttHandler::removeCallbacks)
        sttFinalizeRunnable = null
        pleosSttListener?.let { listener ->
            try { pleosStt?.removeListener(listener) } catch (e: Exception) {
                Log.w(STT_TAG, "session=$sessionId listener removal failed: ${e.message}")
            }
        }
        pleosSttListener = null
        pleosSttStartPending = false
        pleosSttRequestActive = false
        sttSessionFinalizing = false
        sttSessionEnded = false
        onLiveTranscript?.invoke(LiveTranscriptEvent.Ended(sessionId))
        if (publishState) setSttState(state, message) else sttState = state
        Log.i(STT_TAG, "session=$sessionId finalized; next request requires a new explicit action")
        activeSttSessionId = 0L
    }

    private fun setSttState(state: SttState, message: String?) {
        sttState = state
        Log.i(STT_TAG, "state=$state${message?.let { ", message=$it" } ?: ""}")
        onSttStateChanged?.invoke(state, message)
    }

    private fun sttErrorMessage(error: SttErrorType): String = when (error) {
        SttErrorType.MIC_ERROR -> "Microphone capture failed"
        SttErrorType.TIMEOUT -> "Recognition timed out; try again"
        SttErrorType.NETWORK_ERROR -> "Network error (unexpected in ON_DEVICE mode)"
        SttErrorType.PERMISSION_DENIED -> "Pleos STT service permission denied"
        SttErrorType.UNAVAILABLE_LANGUAGE -> "Korean language unavailable"
        SttErrorType.ON_DEVICE_ENGINE_ERROR -> "Pleos on-device engine failed"
        SttErrorType.NOT_SUPPORTED_API -> "This Pleos emulator does not support the STT API"
        else -> "STT error: $error"
    }

    private fun requestSttAudioFocus(): Boolean {
        if (hasSttAudioFocus) return true
        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> Log.i(STT_TAG, "STT audio focus gained")
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            Log.w(STT_TAG, "STT audio focus lost: $change; stopping microphone")
                            stopPleosRecognition()
                        }
                    }
                }
                .build()
            sttAudioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            hasSttAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(STT_TAG, "STT audio focus request result=$result, granted=$hasSttAudioFocus")
            hasSttAudioFocus
        } catch (e: Exception) {
            Log.e(STT_TAG, "STT audio focus request failed", e)
            false
        }
    }

    private fun releaseSttAudioFocus() {
        val request = sttAudioFocusRequest ?: return
        if (hasSttAudioFocus) {
            try {
                val result = audioManager.abandonAudioFocusRequest(request)
                Log.i(STT_TAG, "STT audio focus abandoned: result=$result")
            } catch (e: Exception) {
                Log.e(STT_TAG, "STT audio focus release failed", e)
            }
        }
        hasSttAudioFocus = false
        sttAudioFocusRequest = null
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(STT_TAG, "STT ready")
                        onListeningStateChanged?.invoke(true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(STT_TAG, "STT speech ended")
                        onListeningStateChanged?.invoke(false)
                    }

                    override fun onError(error: Int) {
                        Log.w(STT_TAG, "STT error=$error")
                        onListeningStateChanged?.invoke(false)
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "말씀을 인식하지 못했습니다. 다시 말씀해 주세요."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "입력 시간이 초과되었습니다."
                            SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 장치 오류입니다."
                            else -> "음성 인식 중 문제가 발생했습니다."
                        }
                        onErrorOccurred?.invoke(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged?.invoke(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull() ?: return
                        Log.i(STT_TAG, "STT recognized: \"$spokenText\"")
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            Log.w(STT_TAG, "SpeechRecognizer is unavailable on this device")
        }
    }

    /** 마이크 STT 음성 수신 시작 */
    fun startListening(currentCarModel: HyundaiCarModel) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorOccurred?.invoke("마이크 입력 미지원 환경입니다. 빠른 질문 버튼을 이용하세요.")
            return
        }

        stopSpeaking()
        speechRecognizer?.let { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onListeningStateChanged?.invoke(true) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { onListeningStateChanged?.invoke(false) }
                override fun onError(error: Int) {
                    onListeningStateChanged?.invoke(false)
                    val msg = if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                        "인식된 음성이 없습니다. 버튼을 누르고 다시 말씀하세요."
                    } else {
                        "음성 인식 오류 ($error)"
                    }
                    onErrorOccurred?.invoke(msg)
                }
                override fun onResults(results: Bundle?) {
                    onListeningStateChanged?.invoke(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val query = matches?.firstOrNull()
                    if (!query.isNullOrBlank()) processQuestion(currentCarModel, query)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(STT_TAG, "STT startListening failed", e)
            }
        }
    }

    private fun activePipeline(): RendyQuestionPipeline? =
        if (RendyIntentFeatureFlags.ENABLE_LLM_CLASSIFIER) {
            llmPipeline ?: rulePipeline.also {
                Log.w(INTENT_TAG, "LLM classifier enabled without a valid HTTPS proxy endpoint; using rules")
            }
        } else rulePipeline

    /** 질문 텍스트 분석 및 답변 TTS 재생 */
    fun processQuestion(carModel: HyundaiCarModel, query: String) {
        // Flag OFF (default): keyword rules only, no network. Flag ON: Gemini proxy with rule fallback.
        // Both use the same DB verification policy and follow-up dialogue.
        val pipeline = activePipeline()
        if (pipeline == null) {
            questionCoordinator.cancelPending()
            deliverDatabaseFallback(query, carModel)
            return
        }
        val requestId = questionCoordinator.submit(
            resolve = { pipeline.answer(query, carModel) },
            deliver = { id, answer ->
                // Dialogue state advances only for the answer that is actually spoken.
                pipeline.commit(answer, id)
                // Never log the transcript or proxy payload here; ids and reasons only.
                Log.i(INTENT_TAG, "Question answered: request=$id, model=${carModel.selectionId}, source=${answer.source}, " +
                    "intent=${answer.intentId}, button=${answer.buttonId}, tier=${answer.tier}, reason=${answer.reason}")
                logDialogue("answer", id, answer, pipeline)
                onSpeechResultReceived?.invoke(query, answer.title, answer.text)
                onAnswerDelivered?.invoke(answer)
                speak(answer.text, UTTERANCE_ID_QA)
            }
        )
        Log.i(INTENT_TAG, "Question classification started: request=$requestId, model=${carModel.selectionId}, " +
            "llm=${pipeline === llmPipeline}")
        // Raw STT text (exactly what the recognizer returned) under the same request id as the interpretation
        // line (RendyDialogue "answer: request=..": act/intent/reason), so the two are never confused in logs.
        Log.i(STT_TAG, "STT raw input: request=$requestId, text=$query")
    }

    /**
     * After the vehicle signal reports PARKED: speak the single resume offer for guidance held while driving.
     * Returns false when there is nothing to offer (or it was offered already for this hold).
     */
    fun offerGuidanceResume(carModel: HyundaiCarModel): Boolean {
        val pipeline = activePipeline() ?: return false
        val offer = pipeline.resumeOffer(carModel) ?: return false
        cancelPendingQuestion("resume_offer")
        pipeline.commit(offer)
        logDialogue("resume_offer", null, offer, pipeline)
        onSpeechResultReceived?.invoke("(정차 감지)", offer.title, offer.text)
        onAnswerDelivered?.invoke(offer)
        speak(offer.text, UTTERANCE_ID_QA)
        return true
    }

    /** Act, stage and a hash of the spoken text (for exact-repeat checks in logs); never the driver transcript. */
    private fun logDialogue(kind: String, requestId: Long?, answer: VoiceAnswer, pipeline: RendyQuestionPipeline) {
        val s = pipeline.currentState()
        val driving = try { drivingStateProvider().name } catch (_: Exception) { "ERROR" }
        Log.i(DIALOGUE_TAG, "$kind: request=$requestId, interpreted: act=${answer.act}, intent=${answer.intentId}, source=${answer.source}, stage=${s?.stage}, step=${s?.stepIndex}, " +
            "resumeOffered=${s?.resumeOffered}, driving=$driving, repeatable=${answer.repeatable}, " +
            "asks=${answer.asksQuestion}, ends=${answer.endsConversation}, spokenSha=${sha8(answer.text)}, " +
            "spokenLen=${answer.text.length}, reason=${answer.reason}")
    }

    private fun sha8(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { String.format("%02x", it) }

    /** Forget the follow-up context (e.g. the user picked another car or the app is closing). */
    fun resetConversation(cause: String) {
        cancelPendingQuestion(cause)
        if (RendyIntentFeatureFlags.ENABLE_LLM_CLASSIFIER) llmPipeline?.resetContext()
        rulePipeline?.resetContext()
        Log.i(INTENT_TAG, "Conversation context reset: cause=$cause")
    }

    /** Drop any in-flight LLM answer so it cannot be spoken over a newer interaction. */
    private fun cancelPendingQuestion(cause: String) {
        if (questionCoordinator.cancelPending()) {
            Log.i(INTENT_TAG, "Pending question answer discarded: cause=$cause")
        }
    }

    private fun deliverDatabaseFallback(query: String, carModel: HyundaiCarModel) {
        val (title, answer) = buttonDb.matchVoiceQuery(carModel, query)
        onSpeechResultReceived?.invoke(query, title, answer)
        speak(answer, UTTERANCE_ID_QA)
    }

    /**
     * TTS 발화를 요청합니다. speak()의 SUCCESS는 큐 등록을 뜻하며, 실제 재생은 진행 콜백으로 구분합니다.
     */
    fun speak(
        text: String,
        utteranceId: String = UTTERANCE_ID_BRIEFING,
        onCompleted: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ) {
        val requestedAt = SystemClock.elapsedRealtime()
        val request = SpeechRequest(text, utteranceId, queueMode, onCompleted, onFailed, requestedAt)
        Log.i(TAG, "TTS pipeline: request id=$utteranceId, requestedAt=$requestedAt, ready=$isTtsReady, queueMode=$queueMode")
        if (ttsInitializationFailed) {
            Log.e(TAG, "TTS request rejected because initialization failed: id=$utteranceId")
            onFailed?.invoke("TTS is not ready")
            return
        }
        if (!isTtsReady || tts == null) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                failPendingSpeech("Replaced by a newer QUEUE_FLUSH request")
            }
            pendingSpeech.addLast(request)
            onTtsPlaybackStateChanged?.invoke(true)
            Log.i(TAG, "TTS request queued until initialization: id=$utteranceId")
            return
        }
        dispatchSpeech(request)
    }

    private fun dispatchSpeech(request: SpeechRequest) {
        if (pleosSttRequestActive || pleosSttStartPending) stopPleosRecognition()
        val engine = tts
        if (!isTtsReady || engine == null) {
            Log.e(TAG, "TTS request rejected; Pleos SDK is not ready: id=${request.utteranceId}")
            request.onFailed?.invoke("Pleos TTS engine is not ready")
            return
        }

        if (request.queueMode == TextToSpeech.QUEUE_FLUSH) {
            failPendingSpeech("Replaced by a newer QUEUE_FLUSH request")
            if (activeSpeech.isNotEmpty()) {
                ignoreNextStopEvent = true
                try {
                    engine.stop()
                } catch (e: Exception) {
                    ignoreNextStopEvent = false
                    Log.e(TAG, "Pleos TTS stop before queue flush failed", e)
                }
                failActiveSpeech("Interrupted by a newer QUEUE_FLUSH request")
            }
            releaseAudioFocus()
        } else if (activeSpeech.isNotEmpty()) {
            pendingSpeech.addLast(request)
            onTtsPlaybackStateChanged?.invoke(true)
            Log.i(TAG, "Pleos TTS request queued behind active playback: id=${request.utteranceId}")
            return
        }

        val focusStartedAt = SystemClock.elapsedRealtime()
        if (!requestAudioFocus()) {
            Log.e(TAG, "Audio focus denied; TTS will not play: id=${request.utteranceId}")
            request.onFailed?.invoke("Audio focus was not granted")
            return
        }
        val focusGrantedAt = SystemClock.elapsedRealtime()

        val id = if (request.utteranceId == RENDY_INTRO_UTTERANCE_ID) {
            request.utteranceId
        } else {
            "${request.utteranceId}-${++utteranceSequence}"
        }
        activeSpeech[id] = request
        Log.i(TAG, "TTS pipeline: id=$id, requestToDispatchMs=${focusGrantedAt - request.requestedAtElapsedMs}, audioFocusCallMs=${focusGrantedAt - focusStartedAt}")
        onTtsPlaybackStateChanged?.invoke(true)
        try {
            engine.speak(request.text, true)
            Log.i(TAG, "Pleos TTS speak() dispatched (void API; no result code): id=$id")
        } catch (e: Exception) {
            Log.e(TAG, "Pleos TTS speak() threw for id=$id", e)
            finishUtterance(id, "Pleos TTS speak() exception: ${e.message}")
        }
    }

    private fun dispatchNextPendingSpeech() {
        if (!isTtsReady || activeSpeech.isNotEmpty() || pendingSpeech.isEmpty()) return
        dispatchSpeech(pendingSpeech.removeFirst())
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> Log.i(TAG, "Audio focus gained")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Log.i(TAG, "Audio focus: transient duck allowed")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.w(TAG, "Audio focus lost: change=$change; stopping TTS")
                            tts?.stop()
                            failActiveSpeech("Audio focus lost: $change")
                            releaseAudioFocus()
                        }
                    }
                }
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "Audio focus request result=$result, granted=$hasAudioFocus")
            hasAudioFocus
        } catch (e: Exception) {
            Log.e(TAG, "Audio focus request failed", e)
            false
        }
    }

    private fun releaseAudioFocus() {
        val request = audioFocusRequest ?: return
        if (hasAudioFocus) {
            try {
                val result = audioManager.abandonAudioFocusRequest(request)
                Log.i(TAG, "Audio focus abandoned: result=$result")
            } catch (e: Exception) {
                Log.e(TAG, "Audio focus release failed", e)
            }
        }
        hasAudioFocus = false
        audioFocusRequest = null
    }

    private fun finishUtterance(id: String?, failure: String?) {
        if (id == null) return
        val request = activeSpeech.remove(id) ?: return
        if (failure == null) {
            request.onCompleted?.invoke()
        } else {
            request.onFailed?.invoke(failure)
        }
        if (activeSpeech.isEmpty()) {
            if (pendingSpeech.isNotEmpty() && isTtsReady) {
                dispatchNextPendingSpeech()
            } else {
                onAllSpeechEnded()
                onTtsPlaybackStateChanged?.invoke(false)
            }
        }
    }

    /** Playback finished or was stopped and nothing is queued: release TTS focus; parallel wake STT takes its own. */
    private fun onAllSpeechEnded() {
        lastSpeechDoneAtMs = SystemClock.elapsedRealtime()
        releaseAudioFocus()
        if (activeSttOverlapsPrompt && pleosSttRequestActive && !hasSttAudioFocus) {
            val granted = requestSttAudioFocus()
            Log.i(STT_TAG, "session=$activeSttSessionId parallel STT took audio focus at prompt end: granted=$granted")
        }
    }

    private fun failActiveSpeech(reason: String) {
        val requests = activeSpeech.values.toList()
        activeSpeech.clear()
        if (requests.isNotEmpty() && pendingSpeech.isEmpty()) lastSpeechDoneAtMs = SystemClock.elapsedRealtime()
        requests.forEach { it.onFailed?.invoke(reason) }
        onTtsPlaybackStateChanged?.invoke(isTtsBusy)
    }

    private fun failPendingSpeech(reason: String) {
        while (pendingSpeech.isNotEmpty()) {
            pendingSpeech.removeFirst().onFailed?.invoke(reason)
        }
        onTtsPlaybackStateChanged?.invoke(isTtsBusy)
    }

    fun stopSpeaking() {
        tts?.stop()
        failActiveSpeech("TTS stopped")
        failPendingSpeech("TTS stopped")
        releaseAudioFocus()
        onTtsPlaybackStateChanged?.invoke(false)
    }

    /** Keep the initialized Pleos SDKs warm across Service recreation; only detach Service callbacks. */
    fun detachForServiceStop() {
        onListeningStateChanged = null
        onSpeechResultReceived = null
        onErrorOccurred = null
        onSpokenSubtitle = null
        onSttStateChanged = null
        onTtsPlaybackStateChanged = null
        onSttTextReceived = null
        onSttCaptureReady = null
        onAnswerDelivered = null
        resetConversation("service_detached")
        stopPleosRecognition()
        stopSpeaking()
        Log.i(STT_TAG, "Voice engine detached from Service; Pleos SDK instances retained for app-process reuse")
    }

    fun destroy() {
        try {
            resetConversation("engine_destroyed")
            speechRecognizer?.destroy()
            speechRecognizer = null
            stopPleosRecognition()
            stopSpeaking()
            sttFinalizeRunnable?.let(sttHandler::removeCallbacks)
            sttFinalizeRunnable = null
            pleosSttListener?.let { listener ->
                try { pleosStt?.removeListener(listener) } catch (_: Exception) {}
                pleosSttListener = null
            }
            pleosStt?.let { engine ->
                engine.release()
            }
            pleosStt = null
            pleosSttInitialized = false
            tts?.let { engine ->
                if (sdkEventListenerRegistered) {
                    engine.removeEventListener(pleosEventListener)
                    sdkEventListenerRegistered = false
                }
                engine.release()
            }
            tts = null
            isTtsReady = false
            sdkInitialized = false
            Log.i(TAG, "Pleos TTS SDK and speech recognizer resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Voice engine cleanup failed", e)
        }
    }
}
