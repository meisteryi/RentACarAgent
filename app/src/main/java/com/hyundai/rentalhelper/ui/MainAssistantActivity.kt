package com.hyundai.rentalhelper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hyundai.rentalhelper.R
import com.hyundai.rentalhelper.RentalCoachApplication
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.CarSegment
import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.UserPreferences
import com.hyundai.rentalhelper.databinding.ActivityMainAssistantBinding
import com.hyundai.rentalhelper.service.VehicleSdkManager
import com.hyundai.rentalhelper.service.VehicleState
import com.hyundai.rentalhelper.service.VoiceAssistantEngine
import com.hyundai.rentalhelper.utils.CarUxRestrictionsHelper

/**
 * [현대차 순정 ccNC 내비게이션 + 렌터카 적응 코치 일체형 화면]
 * - 현대 순정 내비게이션 UI (상단 검색/프로필, 좌측 TBT 안내, 우측 맵툴, 하단 도착 바) 100% 준수
 * - 시동 직후 차종 선택 완료 시 본 순정 지도 화면으로 직행
 * - 지도가 열림과 동시에 순정 시스템 알림 팝업 및 백그라운드 음성(TTS)으로 체급 브리핑 자동 송출
 * - 스티어링 휠 / 검색 바의 순정 음성 버튼을 통해 지도를 가리지 않는 온디맨드 음성 Q&A 제공
 */
class MainAssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainAssistantBinding
    private lateinit var userPreferences: UserPreferences
    private lateinit var buttonDb: CarButtonDatabase
    private lateinit var vehicleSdkManager: VehicleSdkManager
    private lateinit var voiceEngine: VoiceAssistantEngine
    private lateinit var uxRestrictionsHelper: CarUxRestrictionsHelper

    private var currentRentalModel: HyundaiCarModel? = null
    private var isBriefingGiven = false
    private var isDrivingMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                currentRentalModel?.let { voiceEngine.startListening(it) }
            } else {
                showQaResult("음성 입력", "음성 인식을 위해 마이크 권한이 필요합니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as RentalCoachApplication
        userPreferences = app.userPreferences
        buttonDb = app.buttonDatabase

        initVoiceEngine()
        initUxRestrictions()
        initVehicleSdk()
        setupClickListeners()
    }

    private fun initVoiceEngine() {
        voiceEngine = VoiceAssistantEngine(this, buttonDb)

        voiceEngine.onListeningStateChanged = { isListening ->
            if (isListening) {
                binding.btnCcncVoice.setColorFilter(ContextCompat.getColor(this, R.color.mic_listening_pulse))
                showQaResult("음성 인식 중", "말씀을 듣고 있습니다… (예: 성에 제거 어떻게 해?)")
            } else {
                binding.btnCcncVoice.setColorFilter(ContextCompat.getColor(this, R.color.hyundai_active_blue))
            }
        }

        voiceEngine.onSpeechResultReceived = { query, title, answer ->
            showQaResult(query, answer)
        }

        voiceEngine.onErrorOccurred = { errorMsg ->
            showQaResult("음성 비서", errorMsg)
        }
    }

    private fun showQaResult(query: String, answer: String) {
        binding.cardVoiceQa.visibility = View.VISIBLE
        binding.tvQaUserQuery.text = "\"$query\""
        binding.tvQaAnswer.text = answer

        // 8초 후 자동으로 Q&A 플로팅 카드 숨김 (순정 지도 시야 유지)
        mainHandler.removeCallbacks(autoHideQaRunnable)
        mainHandler.postDelayed(autoHideQaRunnable, 8000)
    }

    private val autoHideQaRunnable = Runnable {
        binding.cardVoiceQa.visibility = View.GONE
    }

    private fun initUxRestrictions() {
        uxRestrictionsHelper = CarUxRestrictionsHelper(this) { isRestricted, _ ->
            isDrivingMode = isRestricted
            applyCarUxRestrictions(isRestricted)
        }
        uxRestrictionsHelper.startListening()
    }

    private fun applyCarUxRestrictions(isRestricted: Boolean) {
        if (isRestricted) {
            // 주행 중: 운전자 주의분산 방지 (불필요한 칩 숨김, 음성 위주 안내)
            binding.layoutDrivingModeAlert.visibility = View.VISIBLE
            binding.bottomQuickActions.visibility = View.GONE
        } else {
            // 정차 중: 테스트 편의 칩 활성화
            binding.layoutDrivingModeAlert.visibility = View.GONE
            binding.bottomQuickActions.visibility = View.VISIBLE
        }
    }

    private fun initVehicleSdk() {
        vehicleSdkManager = VehicleSdkManager(this) { vehicleState ->
            onVehicleStateChanged(vehicleState)
        }
        vehicleSdkManager.start()
    }

    private fun onVehicleStateChanged(state: VehicleState) {
        val model = buttonDb.findModelById(state.modelId) ?: buttonDb.findModelByNameOrFuzzy(state.modelName)
        currentRentalModel = model

        if (state.isMoving && !isDrivingMode) {
            uxRestrictionsHelper.setSimulatedDriving(true)
        } else if (!state.isMoving && isDrivingMode) {
            uxRestrictionsHelper.setSimulatedDriving(false)
        }

        // [핵심] 순정 지도가 뜨자마자 최초 1회 현대 시스템 알림 카드와 함께 TTS 음성 브리핑 송출!
        if (!isBriefingGiven && model != null) {
            isBriefingGiven = true
            triggerAdaptationBriefing(model)
        }
    }

    /**
     * 현대 순정 시스템 알림 팝업 + 1~2문장 음성 브리핑
     */
    private fun triggerAdaptationBriefing(rentalModel: HyundaiCarModel) {
        val usualSegment = userPreferences.getUsualCarSegment() ?: CarSegment.SEDAN
        val briefingText = buttonDb.generateAdaptationBriefing(rentalModel, usualSegment)

        binding.cardFloatingBriefing.visibility = View.VISIBLE
        binding.tvBriefingHeader.text = "💡 현대 렌터카 적응 코치 (${rentalModel.modelNameKo})"
        binding.tvBriefingContent.text = briefingText
        binding.tvBriefingSubtip.text = "평소 운전: ${usualSegment.displayName} ➔ 현재 렌터카: ${rentalModel.modelNameKo}"

        // 음성(TTS) 즉시 송출
        voiceEngine.speak(briefingText)

        // 10초 후 순정 지도 화면을 위해 알림 카드가 부드럽게 페이드아웃
        mainHandler.postDelayed({
            binding.cardFloatingBriefing.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    binding.cardFloatingBriefing.visibility = View.GONE
                    binding.cardFloatingBriefing.alpha = 1f
                }
        }, 10000)
    }

    private fun setupClickListeners() {
        // 브리핑 닫기 버튼
        binding.btnCloseBriefing.setOnClickListener {
            binding.cardFloatingBriefing.visibility = View.GONE
        }

        // 현대 순정 검색 바의 음성 버튼 (핸들 음성 버튼 대체)
        binding.btnCcncVoice.setOnClickListener {
            handleMicTrigger()
        }

        // 상단 프로필/검색 터치 시에도 음성 인식 트리거
        binding.searchBarPill.setOnClickListener {
            handleMicTrigger()
        }

        // 에뮬레이터 테스트용 칩 버튼 (지도를 보면서 구체적인 위치 질문 즉시 시연)
        binding.chipDefog.setOnClickListener {
            simulateQuery("창문에 성에가 꼈어 어떡하지?")
        }
        binding.chipCruise.setOnClickListener {
            simulateQuery("스마트크루즈 어떻게 켜?")
        }
        binding.chipGear.setOnClickListener {
            simulateQuery("기어 변속 어떻게 해?")
        }
        binding.chipHazard.setOnClickListener {
            simulateQuery("비상등 어디 있어?")
        }
    }

    private fun handleMicTrigger() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            val model = currentRentalModel ?: return
            voiceEngine.startListening(model)
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun simulateQuery(query: String) {
        val model = currentRentalModel ?: return
        voiceEngine.processQuestion(model, query)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        voiceEngine.destroy()
        vehicleSdkManager.stop()
        uxRestrictionsHelper.stopListening()
    }
}
