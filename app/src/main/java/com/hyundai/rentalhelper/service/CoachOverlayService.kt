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
import android.provider.Settings
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
 * [순정 지도 위 플로팅 아이콘 및 상단 브리핑 오버레이 서비스]
 * - 에뮬레이터 기본 순정 내비게이션(Navi42) 화면 위에 직접 떠 있는 플로팅 위젯
 * - 드래그하여 원하는 위치로 이동 가능
 * - 터치 시 퀵 질문 메뉴 오픈 및 음성 질문 지원
 */
class CoachOverlayService : Service() {

    companion object {
        private const val TAG = "CoachOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "rental_car_coach_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_COACHING = "com.hyundai.rentalhelper.action.START_COACHING"
        const val ACTION_TRIGGER_BRIEFING = "com.hyundai.rentalhelper.action.TRIGGER_BRIEFING"
    }

    private var windowManager: WindowManager? = null

    // 1. 순정 지도 위 항상 떠 있는 플로팅 아이콘
    private var bubbleBinding: LayoutFloatingCoachBubbleBinding? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    // 2. 상단 음성 안내 & 답변 팝업 배너
    private var bannerBinding: LayoutFloatingBriefingBannerBinding? = null
    private var bannerLayoutParams: WindowManager.LayoutParams? = null

    private lateinit var userPreferences: UserPreferences
    private lateinit var buttonDb: CarButtonDatabase
    private lateinit var vehicleSdkManager: VehicleSdkManager
    private lateinit var voiceEngine: VoiceAssistantEngine

    private var currentRentalModel: HyundaiCarModel? = null
    private var isBriefingGiven = false
    private val mainHandler = Handler(Looper.getMainLooper())

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

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("현대 렌터카 적응 코칭 비서")
            .setContentText("순정 지도 위에 플로팅 음성 비서가 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

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
            Log.e(TAG, "startForeground 예외 (오버레이는 계속 표시)", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {}
        }
    }

    private fun initVoiceEngine() {
        voiceEngine = VoiceAssistantEngine(this, buttonDb)

        voiceEngine.onSpeechResultReceived = { query, title, answer ->
            showBanner(title, answer, "질문: \"$query\"")
        }

        voiceEngine.onErrorOccurred = { errorMsg ->
            showBanner("음성 비서", errorMsg, "다시 말씀해 주세요.")
        }
    }

    private fun initVehicleSdk() {
        vehicleSdkManager = VehicleSdkManager(this) { vehicleState ->
            val model = buttonDb.findModelById(vehicleState.modelId)
                ?: buttonDb.findModelByNameOrFuzzy(vehicleState.modelName)
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

    /**
     * 순정 지도 우측 하단에 항상 떠 있는 현대 순정형 플로팅 아이콘 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음")
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
            x = 80
            y = 180
        }

        // 터치 및 드래그 제스처 등록
        setupBubbleTouchListener()
        setupBubbleClickListeners()

        try {
            windowManager?.addView(bubbleBinding?.root, bubbleLayoutParams)
            Log.i(TAG, "순정 지도 위 플로팅 아이콘 등록 완료")
        } catch (e: Exception) {
            Log.e(TAG, "플로팅 아이콘 addView 실패", e)
        }
    }

    /**
     * 상단 음성 브리핑 / Q&A 팝업 배너 생성 (평소에는 GONE, 필요 시 노출)
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 24
        }

        bannerBinding?.btnBannerClose?.setOnClickListener {
            bannerBinding?.root?.visibility = View.GONE
        }

        try {
            windowManager?.addView(bannerBinding?.root, bannerLayoutParams)
            Log.i(TAG, "상단 플로팅 배너 등록 완료")
        } catch (e: Exception) {
            Log.e(TAG, "상단 배너 addView 실패", e)
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
                            params.x = initialX + dx
                            params.y = initialY + dy
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
                            // 단순 탭 시 퀵 메뉴 토글
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
            Log.e(TAG, "updateViewLayout 실패", e)
        }
    }

    private fun setupBubbleClickListeners() {
        val binding = bubbleBinding ?: return

        binding.btnQuickDefog.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("창문에 성에가 꼈어 어떡하지?")
        }
        binding.btnQuickCruise.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("스마트크루즈 어떻게 켜?")
        }
        binding.btnQuickGear.setOnClickListener {
            toggleBubbleMenu()
            simulateQuery("기어 변속 어떻게 해?")
        }
        binding.btnQuickBriefing.setOnClickListener {
            toggleBubbleMenu()
            currentRentalModel?.let { triggerAdaptationBriefing(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = currentRentalModel
            ?: buttonDb.findModelById(vehicleSdkManager.getCurrentState().modelId)

        if (model != null && (!isBriefingGiven || intent?.action == ACTION_TRIGGER_BRIEFING)) {
            isBriefingGiven = true
            triggerAdaptationBriefing(model)
        }

        return START_STICKY
    }

    /**
     * [기능 2: 순정 지도 위 상단 배너 노출 + TTS 음성 브리핑]
     */
    private fun triggerAdaptationBriefing(rentalModel: HyundaiCarModel) {
        val usualSegment = userPreferences.getUsualCarSegment() ?: CarSegment.SEDAN
        val briefingText = buttonDb.generateAdaptationBriefing(rentalModel, usualSegment)

        showBanner(
            "💡 현대 렌터카 체급 브리핑 (${rentalModel.modelNameKo})",
            briefingText,
            "평소: ${usualSegment.displayName} ➔ 현재 렌터카: ${rentalModel.modelNameKo}"
        )

        // 차량 스피커 음성(TTS) 즉각 출력
        voiceEngine.speak(briefingText)
    }

    private fun showBanner(title: String, body: String, subtext: String) {
        val banner = bannerBinding ?: return
        banner.tvBannerTitle.text = title
        banner.tvBannerBody.text = body
        banner.tvBannerSubtext.text = subtext
        banner.root.visibility = View.VISIBLE
        try {
            windowManager?.updateViewLayout(banner.root, bannerLayoutParams)
        } catch (e: Exception) {}

        mainHandler.removeCallbacks(autoHideBannerRunnable)
        // 10초 후 순정 지도 시야 확보를 위해 자동 닫힘
        mainHandler.postDelayed(autoHideBannerRunnable, 10000)
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
        showBanner("🎙️ $title", answer, "질문: \"$query\"")
        voiceEngine.speak(answer)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        voiceEngine.destroy()
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
