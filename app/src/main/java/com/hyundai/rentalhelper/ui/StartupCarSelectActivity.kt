package com.hyundai.rentalhelper.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.hyundai.rentalhelper.R
import com.hyundai.rentalhelper.RentalCoachApplication
import com.hyundai.rentalhelper.data.CarSegment
import com.hyundai.rentalhelper.data.UserPreferences
import com.hyundai.rentalhelper.databinding.ActivityStartupCarSelectBinding
import com.hyundai.rentalhelper.service.CoachOverlayService

/**
 * [기능 1: 승차 직전/시동 시 '평소 내 차종' 선택 화면]
 * - 시동 직후 드라이버에게 평소 타던 차종(경차, 세단, SUV, 승합차, 트럭) 선택 제공
 * - 선택 완료 즉시 [에뮬레이터 안의 실제 순정 내비게이션(Navi42)]을 전면에 호출하고
 *   백그라운드 음성 코칭 서비스를 기동하여 지도 위에서 음성 브리핑(TTS) 송출!
 */
class StartupCarSelectActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StartupCarSelectActivity"
        const val EXTRA_FORCE_RECONFIGURE = "extra_force_reconfigure"

        // 에뮬레이터 내 실제 현대/Pleos 순정 내비게이션 패키지 및 액티비티
        private const val NATIVE_NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
        private const val NATIVE_NAVI_ACTIVITY = "ai.umos.maps.android.navigation.app.MainActivity"
    }

    private lateinit var binding: ActivityStartupCarSelectBinding
    private lateinit var userPreferences: UserPreferences

    private var selectedSegment: CarSegment? = null
    private val cardMap = mutableMapOf<CarSegment, MaterialCardView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userPreferences = (application as RentalCoachApplication).userPreferences

        // 시뮬레이터 및 시연 환경: 앱이 켜질 때마다 항상 '시동 직후 차종 선택' 화면이 나타나도록 보장
        binding = ActivityStartupCarSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initCardMappings()
        setupListeners()
    }

    private fun initCardMappings() {
        cardMap[CarSegment.COMPACT] = binding.cardCompact
        cardMap[CarSegment.SEDAN] = binding.cardSedan
        cardMap[CarSegment.SUV] = binding.cardSuv
        cardMap[CarSegment.VAN] = binding.cardVan
        cardMap[CarSegment.TRUCK] = binding.cardTruck

        userPreferences.getUsualCarSegment()?.let {
            selectSegment(it)
        }
    }

    private fun setupListeners() {
        cardMap.forEach { (segment, cardView) ->
            cardView.setOnClickListener {
                selectSegment(segment)
            }
        }

        binding.btnConfirm.setOnClickListener {
            selectedSegment?.let { segment ->
                userPreferences.saveUsualCarSegment(segment)
                launchNativeNavigationWithCoach()
            }
        }

        binding.btnSkip.setOnClickListener {
            if (!userPreferences.isPresetConfigured()) {
                userPreferences.saveUsualCarSegment(CarSegment.SEDAN)
            }
            launchNativeNavigationWithCoach()
        }
    }

    private fun selectSegment(segment: CarSegment) {
        selectedSegment = segment

        val activeColor = ContextCompat.getColor(this, R.color.hyundai_active_blue)
        val defaultStroke = ContextCompat.getColor(this, R.color.car_surface_dark_stroke)
        val selectedCardBg = ContextCompat.getColor(this, R.color.car_card_selected)
        val defaultCardBg = ContextCompat.getColor(this, R.color.car_surface_dark)

        cardMap.forEach { (seg, card) ->
            if (seg == segment) {
                card.strokeColor = activeColor
                card.strokeWidth = 4
                card.setCardBackgroundColor(selectedCardBg)
            } else {
                card.strokeColor = defaultStroke
                card.strokeWidth = 2
                card.setCardBackgroundColor(defaultCardBg)
            }
        }

        binding.btnConfirm.isEnabled = true
        binding.btnConfirm.alpha = 1.0f
    }

    /**
     * [핵심] 에뮬레이터 내 실제 순정 내비게이션(Navi42) 실행 + 백그라운드 음성 코칭 기동
     */
    private fun launchNativeNavigationWithCoach() {
        // 1. 순정 내비 위 음성 브리핑 & 플로팅 비서 서비스 기동
        val coachIntent = Intent(this, CoachOverlayService::class.java).apply {
            action = CoachOverlayService.ACTION_START_COACHING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, coachIntent)
        } else {
            startService(coachIntent)
        }

        // 2. 에뮬레이터에 설치된 실제 현대 순정 내비게이션 앱 실행
        var navIntent: Intent? = null
        try {
            navIntent = Intent().apply {
                component = ComponentName(NATIVE_NAVI_PACKAGE, NATIVE_NAVI_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(navIntent)
            Log.i(TAG, "실제 현대 순정 내비게이션(Navi42) 호출 성공")
        } catch (e: Exception) {
            Log.w(TAG, "순정 내비 호출 실패, 시스템 맵 인텐트로 폴백 시도", e)
            try {
                val fallbackIntent = packageManager.getLaunchIntentForPackage(NATIVE_NAVI_PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:37.5665,126.9780"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
            } catch (fallbackEx: Exception) {
                // 앱 내부 내비 화면으로 폴백
                val internalNav = Intent(this, MainAssistantActivity::class.java)
                startActivity(internalNav)
            }
        }

        finish()
    }
}
