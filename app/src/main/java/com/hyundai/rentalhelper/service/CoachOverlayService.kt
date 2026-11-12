package com.hyundai.rentalhelper.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.hyundai.rentalhelper.RentalCoachApplication
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.CarSegment
import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.UserPreferences
import com.hyundai.rentalhelper.databinding.LayoutFloatingBriefingBannerBinding
import com.hyundai.rentalhelper.databinding.LayoutFloatingCoachBubbleBinding

/**
 * [Floating coach overlay service above the native navigation surface]
 * - Displays a floating widget over the native Navi42 navigation screen
 * - Can be dragged to a preferred position
 * - Opens quick questions and voice input when tapped
 */
class CoachOverlayService : Service() {

    companion object {
        private const val TAG = "CoachOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "rental_car_coach_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_PROMPT_TEXT = RendyVoicePrompts.WAKE_PROMPT_TEXT
        private const val WAKE_PROMPT_UTTERANCE_ID = "rendy_wake_prompt"
        private const val LISTENING_STATUS_HOLD_MS = 550L
        private const val RETRY_PROMPT_DELAY_MS = 300L
        private const val RETRY_STATUS_DURATION_MS = 2_500L

        const val ACTION_START_COACHING = "com.hyundai.rentalhelper.action.START_COACHING"
        const val ACTION_TRIGGER_BRIEFING = "com.hyundai.rentalhelper.action.TRIGGER_BRIEFING"
        const val ACTION_VOICE_QUERY = "com.hyundai.rentalhelper.action.VOICE_QUERY"
        const val ACTION_TEST_RENDY_GREETING = "com.hyundai.rentalhelper.action.TEST_RENDY_GREETING"
        const val ACTION_TOGGLE_PLEOS_STT = "com.hyundai.rentalhelper.action.TOGGLE_PLEOS_STT"
        const val ACTION_START_PLEOS_STT = "com.hyundai.rentalhelper.action.START_PLEOS_STT"
        /** Debug builds only: runs the post-detection wake flow (prompt TTS -> Pleos STT) without Vosk audio. */
        const val ACTION_SIMULATE_WAKE_WORD = "com.hyundai.rentalhelper.action.SIMULATE_WAKE_WORD"
        /** Debug builds only: offline wake-word detection test on WAV files in files/wakeeval (no microphone). */
        const val ACTION_WAKEWORD_SELFTEST = "com.hyundai.rentalhelper.action.WAKEWORD_SELFTEST"
        const val EXTRA_QUERY = "query"
    }

    private var windowManager: WindowManager? = null

    // Floating widget above native navigation
    private var bubbleBinding: LayoutFloatingCoachBubbleBinding? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    // Voice guidance and answer banner
    private var bannerBinding: LayoutFloatingBriefingBannerBinding? = null
    private var bannerLayoutParams: WindowManager.LayoutParams? = null

    private lateinit var userPreferences: UserPreferences
    private lateinit var buttonDb: CarButtonDatabase
    private lateinit var vehicleSdkManager: VehicleSdkManager
    private lateinit var voiceEngine: VoiceAssistantEngine

    private var currentRentalModel: HyundaiCarModel? = null
    private var isBriefingGiven = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sttNotificationText = "Navigation coach running"
    private var wakeWordController: RendyWakeWordController? = null
    private val listeningCue = RendyListeningCue()
    private var wakeResumeRunnable: Runnable? = null
    private var isServiceDestroying = false
    private var voiceStatusOverlay: RendyVoiceStatusOverlay? = null
    private var wakeInteractionActive = false
    private var wakePromptPending = false
    private var wakePromptPlaybackDoneAtMs = 0L
    private var wakeSttRetryCount = 0
    private var wakeAnswerLookup = false
    private var wakeUxSessionId = 0L
    private var listeningStatusRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as RentalCoachApplication
        userPreferences = app.userPreferences
        buttonDb = app.buttonDatabase

        startForegroundNotification()
        initVoiceEngine()
        initVehicleSdk()
        initWindowManager()
        createFloatingBubble()
        createFloatingBanner()
        initWakeWord()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "현대 렌터카 적응 코치",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "순정 지도 위 플로팅 음성 비서"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = buildForegroundNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; overlay may still be visible", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {}
        }
    }


    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, com.hyundai.rentalhelper.ui.MicrophonePermissionActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            71,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RentACarAgent voice assistant")
            .setContentText(sttNotificationText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Push to talk", pendingIntent)
            .build()
    }

    private fun refreshSttNotification(text: String) {
        sttNotificationText = text
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun initVoiceEngine() {
        voiceEngine = (application as RentalCoachApplication).voiceAssistantEngine
        voiceEngine.onTtsPlaybackStateChanged = { isBusy ->
            mainHandler.post {
                if (isServiceDestroying) return@post
                if (isBusy) {
                    stopWakeWordListening("TTS playback")
                } else if (wakePromptPending) {
                    startWakeSttAfterPrompt()
                } else if (wakeInteractionActive && !voiceEngine.isSttBusy) {
                    finishWakeInteraction("voice response completed")
                } else if (!wakeInteractionActive) {
                    scheduleWakeWordResume("TTS completed", 500L)
                }
            }
        }

        voiceEngine.onSpokenSubtitle = { spokenText ->
            mainHandler.post {
            if (wakeInteractionActive && wakeAnswerLookup) {
                showWakeVoiceStatus(RendyVoicePrompts.ANSWERING_STATUS, animate = true)
                Log.i("RendyVoiceUX", "Voice answer playback started")
            }
            val carName = currentRentalModel?.modelNameKo ?: "현대 렌터카"
            showBanner(
                title = "[Live speech subtitle - TTS playback]",
                body = spokenText,
                subtext = "Live subtitle for the spoken vehicle guidance.",
                category = "💡 $carName 음성 Q&A 답변"
            )
            }
        }

        voiceEngine.onSpeechResultReceived = { query, title, answer ->
            mainHandler.post {
            if (wakeInteractionActive && wakeAnswerLookup) {
                showWakeVoiceStatus(RendyVoicePrompts.ANSWERING_STATUS, animate = true)
                Log.i("RendyVoiceUX", "Voice answer playback started")
            }
            val carName = currentRentalModel?.modelNameKo ?: "현대 렌터카"
            showBanner(
                title = "[Voice answer - $title]",
                body = answer,
                subtext = "질문: \"$query\"",
                category = "$carName voice Q&A answer"
            )
            }
        }

        voiceEngine.onSttStateChanged = { state, message ->
            mainHandler.post {
                val indicator = when (state) {
                    VoiceAssistantEngine.SttState.IDLE -> "MIC"
                    VoiceAssistantEngine.SttState.LISTENING -> "STOP"
                    VoiceAssistantEngine.SttState.PROCESSING -> "..."
                    VoiceAssistantEngine.SttState.RESULT -> "OK"
                    VoiceAssistantEngine.SttState.ERROR -> "!"
                }
                bubbleBinding?.btnMicPtt?.text = indicator
                refreshSttNotification("STT $state${message?.let { ": $it" } ?: ""}")

                if (wakeInteractionActive) {
                    when (state) {
                        VoiceAssistantEngine.SttState.LISTENING -> {
                            showWakeVoiceStatus(RendyVoicePrompts.LISTENING_STATUS, animate = true)
                            scheduleListeningStatusAdvance()
                        }
                        VoiceAssistantEngine.SttState.PROCESSING -> {
                            if (message == "Processing speech") showWakeVoiceStatus(RendyVoicePrompts.HEARING_STATUS, animate = true)
                        }
                        VoiceAssistantEngine.SttState.RESULT -> {
                            wakeAnswerLookup = true
                            cancelListeningStatusAdvance()
                            showWakeVoiceStatus(RendyVoicePrompts.ANSWER_LOOKUP_STATUS, animate = true)
                        }
                        VoiceAssistantEngine.SttState.IDLE,
                        VoiceAssistantEngine.SttState.ERROR -> {
                            cancelListeningStatusAdvance()
                            if (!voiceEngine.isTtsBusy && !voiceEngine.isSttBusy) {
                                if (state == VoiceAssistantEngine.SttState.ERROR) {
                                    offerWakeSttRetry(message)
                                } else {
                                    finishWakeInteraction("STT $state")
                                }
                            }
                        }
                    }
                } else if (state == VoiceAssistantEngine.SttState.IDLE ||
                    state == VoiceAssistantEngine.SttState.RESULT ||
                    state == VoiceAssistantEngine.SttState.ERROR
                ) {
                    scheduleWakeWordResume("STT $state", 900L)
                }
            }
        }

        voiceEngine.onSttCaptureReady = { trigger ->
            mainHandler.post {
                // Status "네, 말씀하세요!" is shown on SttState.LISTENING, which is set at the same confirmed-capture moment.
                if (!isServiceDestroying && com.hyundai.rentalhelper.BuildConfig.RENDY_READY_CUE == RendyListeningCue.MODE_AFTER_CAPTURE) {
                    listeningCue.play()
                    Log.i("RendyVoiceUX", "Ready cue requested after confirmed capture: trigger=$trigger")
                }
            }
        }

        voiceEngine.onSttTextReceived = { transcript ->
            mainHandler.post {
                Log.i("RendySTT", "Final transcript delivered to onSttTextReceived: $transcript")
                if (wakeInteractionActive) {
                    wakeAnswerLookup = true
                    cancelListeningStatusAdvance()
                    showWakeVoiceStatus(RendyVoicePrompts.ANSWER_LOOKUP_STATUS, animate = true)
                }
                val model = currentRentalModel
                    ?: buttonDb.findModelById(vehicleSdkManager.getCurrentState().modelId)
                if (model == null) {
                    Log.w("RendySTT", "Cannot answer transcript because the current rental car model is unavailable")
                } else {
                    voiceEngine.processQuestion(model, transcript)
                }
            }
        }

        voiceEngine.onErrorOccurred = { errorMsg ->
            showBanner("Voice assistant notice", errorMsg, "Please try again.")
        }
    }

    private fun initVehicleSdk() {
        vehicleSdkManager = VehicleSdkManager(this) { vehicleState ->
            val model = buttonDb.findModelById(vehicleState.modelId)
                ?: buttonDb.findModelByNameOrFuzzy(vehicleState.modelName)
            if (currentRentalModel != null && model?.modelId != currentRentalModel?.modelId) {
                voiceEngine.resetConversation("car_changed")
            }
            currentRentalModel = model

            bubbleBinding?.tvBubbleBadge?.text = model?.modelNameKo ?: vehicleState.modelName

            if (!isBriefingGiven && model != null) {
                isBriefingGiven = true
                triggerAdaptationBriefing(model)
            }
        }
        vehicleSdkManager.start()
    }

    private fun initWindowManager() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun initWakeWord() {
        wakeWordController = RendyWakeWordController(
            this,
            onDetected = { detection ->
                val detectedOnMainQueueAt = SystemClock.elapsedRealtime()
                mainHandler.postAtFrontOfQueue {
                    handleWakeDetected(detection.text, detection.confidence, detection.detectedAtElapsedMs,
                        detection.firstVoiceAtElapsedMs, detectedOnMainQueueAt)
                }
            },
            onListening = {
                mainHandler.post {
                    if (!isServiceDestroying && !voiceEngine.isTtsBusy && !voiceEngine.isSttBusy) {
                        Log.i("RendyWakeWord", "Wake-word microphone is capture-ready")
                        refreshSttNotification("Wake word listening")
                    }
                }
            }
        )
        wakeWordController?.prepare()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scheduleWakeWordResume("service started", 400L)
        } else {
            Log.i("RendyWakeWord", "Wake word idle until RECORD_AUDIO is granted; PTT permission flow remains available")
        }
    }

    /**
     * Wake word "렌디" detected (Vosk, or the debug-only ACTION_SIMULATE_WAKE_WORD): speak
     * [RendyVoicePrompts.WAKE_PROMPT_TEXT], then open Pleos STT only after that playback ends
     * (onTtsPlaybackStateChanged -> startWakeSttAfterPrompt).
     */
    private fun handleWakeDetected(
        text: String,
        confidence: Float,
        detectedAtElapsedMs: Long,
        firstVoiceAtElapsedMs: Long,
        detectedOnMainQueueAt: Long
    ) {
        if (isServiceDestroying || wakeInteractionActive) return
        val promptRequestAt = SystemClock.elapsedRealtime()
        Log.i(
            "RendyVoiceUX",
            "Wake pipeline: audioToMainMs=${detectedOnMainQueueAt - detectedAtElapsedMs}, voiceToPromptRequestMs=${if (firstVoiceAtElapsedMs > 0L) promptRequestAt - firstVoiceAtElapsedMs else -1L}, text=${text}, confidence=${confidence}"
        )
        Log.i("RendyWakeWord", "Microphone released; starting spoken response before Pleos STT")
        wakeInteractionActive = true
        wakeAnswerLookup = false
        wakePromptPending = true
        wakePromptPlaybackDoneAtMs = 0L
        wakeSttRetryCount = 0
        wakeUxSessionId++
        cancelListeningStatusAdvance()
        showWakeVoiceStatus(RendyVoicePrompts.PREPARING_STATUS, animate = true)
        Log.i("RendyVoiceUX", "Wake prompt requested: handlerMs=${SystemClock.elapsedRealtime() - promptRequestAt}")
        voiceEngine.speak(
            text = WAKE_PROMPT_TEXT,
            utteranceId = WAKE_PROMPT_UTTERANCE_ID,
            queueMode = TextToSpeech.QUEUE_FLUSH,
            onCompleted = {
                wakePromptPlaybackDoneAtMs = SystemClock.elapsedRealtime()
                Log.i("RendyVoiceUX", "Wake prompt playback completed; waiting for TTS audio focus release")
            },
            onFailed = { reason ->
                Log.e("RendyVoiceUX", "Wake prompt failed; continuing to STT when audio is released: $reason")
                mainHandler.post { startWakeSttAfterPrompt() }
            }
        )
    }

    private fun startWakeSttAfterPrompt() {
        if (!wakeInteractionActive || !wakePromptPending || isServiceDestroying) return
        if (voiceEngine.isTtsBusy) {
            Log.i("RendyVoiceUX", "Waiting for TTS playback and audio focus to finish before opening STT")
            return
        }
        wakePromptPending = false
        val now = SystemClock.elapsedRealtime()
        val promptEndToSttRequestMs = if (wakePromptPlaybackDoneAtMs > 0L) now - wakePromptPlaybackDoneAtMs else -1L
        Log.i("RendyVoiceUX", "Wake prompt/focus release to Pleos STT request: elapsedMs=$promptEndToSttRequestMs, retry=$wakeSttRetryCount")
        Log.i("RendyVoiceUX", "Wake prompt finished; handing microphone to Pleos STT")
        showWakeVoiceStatus(RendyVoicePrompts.PREPARING_STATUS, animate = true)
        val trigger = if (wakeSttRetryCount == 0) "WAKE_WORD_RENDI" else "WAKE_WORD_RETRY"
        val diagnosticDelay = debugSttDelayMs
        debugSttDelayMs = 0L
        if (diagnosticDelay > 0L) {
            // Debug-only latency experiment (SIMULATE_WAKE_WORD extra "stt_delay_ms"); never set in normal use.
            val session = wakeUxSessionId
            Log.i("RendyVoiceUX", "Diagnostic STT request delay: ${diagnosticDelay}ms")
            mainHandler.postDelayed({
                if (wakeInteractionActive && session == wakeUxSessionId && !isServiceDestroying) voiceEngine.startPleosRecognition(trigger)
            }, diagnosticDelay)
        } else {
            voiceEngine.startPleosRecognition(trigger)
        }
    }

    /** Debug builds only: extra wait before the wake STT request, for latency experiments. */
    private var debugSttDelayMs = 0L

    private fun offerWakeSttRetry(reason: String?) {
        if (!wakeInteractionActive || isServiceDestroying) return
        if (wakeSttRetryCount == 0) {
            wakeSttRetryCount = 1
            wakePromptPending = true
            wakePromptPlaybackDoneAtMs = 0L
            wakeAnswerLookup = false
            showWakeVoiceStatus("잘 못 들었어요. 다시 말씀해 주세요.", animate = true)
            Log.i("RendyVoiceUX", "Offering one spoken STT retry: reason=$reason")
            mainHandler.postDelayed({
                if (!wakeInteractionActive || !wakePromptPending || isServiceDestroying) return@postDelayed
                if (voiceEngine.isSttBusy || voiceEngine.isTtsBusy) {
                    Log.i("RendyVoiceUX", "Retry prompt deferred because audio/session is still busy")
                    return@postDelayed
                }
                voiceEngine.speak(
                    text = RendyVoicePrompts.RETRY_PROMPT_TEXT,
                    utteranceId = "rendy_stt_retry_prompt",
                    queueMode = TextToSpeech.QUEUE_FLUSH,
                    onCompleted = {
                        wakePromptPlaybackDoneAtMs = SystemClock.elapsedRealtime()
                        Log.i("RendyVoiceUX", "Retry prompt playback completed")
                    },
                    onFailed = { failure ->
                        Log.e("RendyVoiceUX", "Retry prompt failed; STT will start if the engine is idle: $failure")
                        mainHandler.post { startWakeSttAfterPrompt() }
                    }
                )
            }, RETRY_PROMPT_DELAY_MS)
        } else {
            wakePromptPending = false
            showWakeVoiceStatus("질문을 듣지 못했어요. 마이크 버튼으로 다시 말해 주세요.", animate = false)
            Log.w("RendyVoiceUX", "Wake STT retry exhausted: reason=$reason; PTT remains available")
            mainHandler.postDelayed({
                if (wakeInteractionActive && !voiceEngine.isTtsBusy && !voiceEngine.isSttBusy) {
                    finishWakeInteraction("STT retry exhausted")
                }
            }, RETRY_STATUS_DURATION_MS)
        }
    }

    private fun showWakeVoiceStatus(message: String, animate: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w("RendyVoiceUX", "Overlay permission unavailable; voice prompt/STT continue without overlay")
            return
        }
        try {
            val overlay = voiceStatusOverlay ?: RendyVoiceStatusOverlay(this, windowManager!!)
                .also { voiceStatusOverlay = it }
            overlay.show(message, animate)
        } catch (e: Exception) {
            Log.e("RendyVoiceUX", "Could not update voice status overlay", e)
        }
    }

    private fun scheduleListeningStatusAdvance() {
        cancelListeningStatusAdvance()
        val session = wakeUxSessionId
        listeningStatusRunnable = Runnable {
            if (wakeInteractionActive && session == wakeUxSessionId && voiceEngine.isSttBusy) {
                showWakeVoiceStatus(RendyVoicePrompts.HEARING_STATUS, animate = true)
            }
        }.also { mainHandler.postDelayed(it, LISTENING_STATUS_HOLD_MS) }
    }

    private fun cancelListeningStatusAdvance() {
        listeningStatusRunnable?.let(mainHandler::removeCallbacks)
        listeningStatusRunnable = null
    }

    private fun finishWakeInteraction(reason: String) {
        if (!wakeInteractionActive) return
        wakeInteractionActive = false
        wakePromptPending = false
        wakeAnswerLookup = false
        wakeUxSessionId++
        cancelListeningStatusAdvance()
        voiceStatusOverlay?.hide()
        Log.i("RendyVoiceUX", "Wake voice interaction ended: $reason; returning to wake-word standby")
        if (!isServiceDestroying) scheduleWakeWordResume("wake interaction ended: $reason", 650L)
    }

    private fun promoteForegroundServiceForMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
            Log.i("RendyWakeWord", "Foreground service microphone type active with RECORD_AUDIO permission")
        } catch (e: Exception) {
            Log.e("RendyWakeWord", "Could not activate microphone foreground service; wake word disabled", e)
        }
    }

    private fun stopWakeWordListening(reason: String) {
        wakeResumeRunnable?.let(mainHandler::removeCallbacks)
        wakeResumeRunnable = null
        wakeWordController?.stop()
        refreshSttNotification("Wake word paused: $reason")
        Log.i("RendyWakeWord", "Wake word listening paused: $reason")
    }

    private fun scheduleWakeWordResume(reason: String, delayMs: Long) {
        if (isServiceDestroying) return
        wakeResumeRunnable?.let(mainHandler::removeCallbacks)
        wakeResumeRunnable = Runnable {
            if (isServiceDestroying) return@Runnable
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w("RendyWakeWord", "Wake word not resumed: RECORD_AUDIO permission missing")
                return@Runnable
            }
            if (::voiceEngine.isInitialized && voiceEngine.isTtsBusy) {
                Log.i("RendyWakeWord", "Wake word not resumed while TTS is active")
                return@Runnable
            }
            if (::voiceEngine.isInitialized && voiceEngine.isSttBusy) {
                Log.i("RendyWakeWord", "Wake word not resumed until the Pleos STT session has released the microphone")
                return@Runnable
            }
            val controller = wakeWordController
            if (controller?.isConfigured() != true) {
                Log.i("RendyWakeWord", "Wake word prerequisites missing; PTT remains available ($reason)")
                return@Runnable
            }
            promoteForegroundServiceForMicrophone()
            val started = controller.start()
            if (started) refreshSttNotification(if (controller.isRunning) "Wake word listening" else "Wake word preparing")
            else Log.i("RendyWakeWord", "Wake word remains unavailable; existing PTT is unchanged ($reason)")
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    /**
     * Creates the floating icon shown above the native navigation surface.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        val themeContext = ContextThemeWrapper(this, com.hyundai.rentalhelper.R.style.Theme_RentalCarAdaptationCoach)
        bubbleBinding = LayoutFloatingCoachBubbleBinding.inflate(LayoutInflater.from(themeContext))

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bubbleLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 100
            y = 240
        }

        // Attach the overlay to the current display
        setupBubbleTouchListener()
        setupBubbleClickListeners()

        try {
            windowManager?.addView(bubbleBinding?.root, bubbleLayoutParams)
            Log.i(TAG, "Floating coach bubble attached")
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach floating coach bubble", e)
        }
    }

    /**
     * Creates the voice guidance and Q&A banner.
     */
    private fun createFloatingBanner() {
        if (!Settings.canDrawOverlays(this)) return

        val themeContext = ContextThemeWrapper(this, com.hyundai.rentalhelper.R.style.Theme_RentalCarAdaptationCoach)
        bannerBinding = LayoutFloatingBriefingBannerBinding.inflate(LayoutInflater.from(themeContext))
        bannerBinding?.root?.visibility = View.GONE

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bannerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 80
            y = 30
        }

        bannerBinding?.btnBannerClose?.setOnClickListener {
            bannerBinding?.root?.visibility = View.GONE
        }

        try {
            windowManager?.addView(bannerBinding?.root, bannerLayoutParams)
            Log.i(TAG, "Floating briefing banner attached")
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach floating briefing banner", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener() {
        val bubble = bubbleBinding?.bubbleIconContainer ?: return

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val params = bubbleLayoutParams ?: return false
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (initialTouchX - event.rawX).toInt()
                        val dy = (initialTouchY - event.rawY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = (initialX + dx).coerceIn(40, 1600)
                            params.y = (initialY + dy).coerceIn(80, 1100)
                            try {
                                windowManager?.updateViewLayout(bubbleBinding?.root, params)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Keep the overlay within the visible display bounds
                            toggleBubbleMenu()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleBubbleMenu() {
        val panel = bubbleBinding?.bubbleMenuPanel ?: return
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        try {
            windowManager?.updateViewLayout(bubbleBinding?.root, bubbleLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed", e)
        }
    }

    private fun setupBubbleClickListeners() {
        val binding = bubbleBinding ?: return

        binding.btnMicPtt.setOnClickListener { requestPleosSttFromUserAction() }

        binding.btnQuickDefog.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("앞유리 김 서렸어. 어떻게 없애?")
        }
        binding.btnQuickCruise.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("크루즈 컨트롤은 어떻게 켜?")
        }
        binding.btnQuickGear.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("기어 변속은 어떻게 해?")
        }
        binding.btnQuickBriefing.setOnClickListener {
            toggleBubbleMenu()
            currentRentalModel?.let { triggerAdaptationBriefing(it) }
        }
    }

    private fun requestPleosSttFromUserAction() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopWakeWordListening("permission request")
            Log.i("RendySTT", "PTT clicked; opening Android runtime microphone permission UI")
            startActivity(Intent(this, com.hyundai.rentalhelper.ui.MicrophonePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        togglePttRecognition("PTT_BUTTON")
    }

    private fun togglePttRecognition(trigger: String) {
        if (voiceEngine.isSttBusy) {
            stopWakeWordListening("PTT stopping recognition")
            voiceEngine.stopPleosRecognition()
            return
        }
        promoteForegroundServiceForMicrophone()
        val controller = wakeWordController
        if (controller == null) {
            voiceEngine.startPleosRecognition(trigger)
            return
        }
        controller.stopAndRunAfterRelease {
            mainHandler.post {
                if (!isServiceDestroying) voiceEngine.startPleosRecognition(trigger)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PLEOS_STT || intent?.action == ACTION_START_PLEOS_STT) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (intent.action == ACTION_START_PLEOS_STT) togglePttRecognition("PTT_ACTION")
                else togglePttRecognition("PTT_TOGGLE_ACTION")
            } else {
                stopWakeWordListening("permission request")
                startActivity(Intent(this, com.hyundai.rentalhelper.ui.MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_WAKEWORD_SELFTEST) {
            if (com.hyundai.rentalhelper.BuildConfig.DEBUG) {
                Thread({ runCatching { RendyWakeWordSelfTest.run(applicationContext) }
                    .onFailure { Log.e(TAG, "Wake self-test failed", it) } }, "RendyWakeSelfTest").start()
            } else {
                Log.w(TAG, "Wake self-test ignored in release builds")
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_SIMULATE_WAKE_WORD) {
            if (com.hyundai.rentalhelper.BuildConfig.DEBUG) {
                Log.i(TAG, "Debug wake-word simulation: same handler as a Vosk detection (acoustic detection itself not exercised)")
                stopWakeWordListening("simulated wake word")
                debugSttDelayMs = intent.getLongExtra("stt_delay_ms", 0L).coerceIn(0L, 5_000L)
                val now = SystemClock.elapsedRealtime()
                handleWakeDetected("렌디(simulated)", 1.0f, now, -1L, now)
            } else {
                Log.w(TAG, "Wake-word simulation ignored in release builds")
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_RENDY_GREETING) {
            Log.i(TAG, "Manual RENDY greeting test requested; Navi42 visibility is not observable here")
            voiceEngine.speak(
                text = VoiceAssistantEngine.RENDY_INTRO_TEXT,
                utteranceId = "rendy_intro_manual_test",
                queueMode = TextToSpeech.QUEUE_FLUSH,
                onFailed = { reason -> Log.e(TAG, "Manual RENDY greeting failed: $reason") }
            )
            return START_STICKY
        }

        if (intent == null || intent.action == ACTION_START_COACHING) {
            val app = application as RentalCoachApplication
            if (app.claimRendyIntroForCurrentSession()) {
                Log.i(TAG, "Automatic RENDY intro requested after Service start; no Navi42 foreground-ready signal is available")
                voiceEngine.speak(
                    text = VoiceAssistantEngine.RENDY_INTRO_TEXT,
                    utteranceId = VoiceAssistantEngine.RENDY_INTRO_UTTERANCE_ID,
                    queueMode = TextToSpeech.QUEUE_ADD,
                    onFailed = { reason -> Log.e(TAG, "Automatic RENDY intro failed: $reason") }
                )
            }
        }

        val model = currentRentalModel
            ?: buttonDb.findModelById(vehicleSdkManager.getCurrentState().modelId)

        if (intent?.action == ACTION_VOICE_QUERY) {
            val query = intent.getStringExtra(EXTRA_QUERY) ?: ""
            if (query.isNotBlank()) {
                // Same entry as a final STT transcript (classifier -> DB policy -> dialogue -> TTS), so text
                // injection exercises the real question path. The bubble demo buttons still use simulateQuery().
                if (model != null) voiceEngine.processQuestion(model, query) else simulateQuery(query)
            }
            return START_STICKY
        }

        if (model != null && (!isBriefingGiven || intent?.action == ACTION_TRIGGER_BRIEFING)) {
            isBriefingGiven = true
            triggerAdaptationBriefing(model)
        }

        return START_STICKY
    }

    /**
     * Starts phase 2: native navigation, floating Q&A, and voice briefing.
     */
    private fun triggerAdaptationBriefing(rentalModel: HyundaiCarModel) {
        val usualSegment = userPreferences.getUsualCarSegment() ?: CarSegment.SEDAN
        val briefingText = buttonDb.generateAdaptationBriefing(rentalModel, usualSegment)

        showBanner(
            title = "[Voice briefing - vehicle adaptation]",
            body = briefingText,
            subtext = "Selected segment: ${usualSegment.displayName}; model: ${rentalModel.modelNameKo}",
            category = "💡 ${rentalModel.modelNameKo} 음성 안내"
        )

        // Read the finished voice guide using Pleos TTS
        // Keep this after/alongside the one-time greeting instead of flushing it.
        voiceEngine.speak(briefingText, queueMode = TextToSpeech.QUEUE_ADD)
    }

    private fun showBanner(
        title: String,
        body: String,
        subtext: String,
        category: String? = null
    ) {
        val banner = bannerBinding ?: return
        banner.tvBannerTitle.text = title
        banner.tvBannerBody.text = "\"$body\""
        banner.tvBannerSubtext.text = subtext
        val carName = currentRentalModel?.modelNameKo ?: "현대 렌터카"
        banner.tvBannerCategory.text = category ?: "$carName voice guidance"
        banner.root.visibility = View.VISIBLE
        try {
            windowManager?.updateViewLayout(banner.root, bannerLayoutParams)
        } catch (e: Exception) {}

        mainHandler.removeCallbacks(autoHideBannerRunnable)
        // Automatically hide the banner after the voice response
        mainHandler.postDelayed(autoHideBannerRunnable, 15000)
    }

    private val autoHideBannerRunnable = Runnable {
        bannerBinding?.let { banner ->
            banner.root.visibility = View.GONE
            try {
                windowManager?.updateViewLayout(banner.root, bannerLayoutParams)
            } catch (e: Exception) {}
        }
    }

    private fun simulateQuery(query: String) {
        val model = currentRentalModel ?: return
        val (title, answer) = buttonDb.matchVoiceQuery(model, query)
        showBanner(
            title = "[Voice answer - $title]",
            body = answer,
            subtext = "질문: \"$query\"",
            category = "${model.modelNameKo} voice guidance"
        )
        voiceEngine.speak(answer)
    }

    override fun onDestroy() {
        isServiceDestroying = true
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Service stopping; releasing TTS/audio focus resources")
        wakeWordController?.release()
        wakeWordController = null
        cancelListeningStatusAdvance()
        voiceStatusOverlay?.destroy()
        voiceStatusOverlay = null
        voiceEngine.detachForServiceStop()
        vehicleSdkManager.stop()

        bubbleBinding?.root?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        bannerBinding?.root?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
