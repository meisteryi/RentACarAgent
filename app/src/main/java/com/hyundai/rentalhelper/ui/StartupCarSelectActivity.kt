package com.hyundai.rentalhelper.ui

import android.content.Intent
import android.os.Bundle
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
 * - 선택 완료 후 [반납지 및 반납 일시 입력 화면(ReturnInfoActivity)]으로 이동하며,
 *   반납 정보 입력까지 마친 뒤에 순정 내비게이션(Navi42) 및 음성 브리핑을 기동합니다.
 */
class StartupCarSelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_RECONFIGURE = "extra_force_reconfigure"
    }

    private lateinit var binding: ActivityStartupCarSelectBinding
    private lateinit var userPreferences: UserPreferences

    private var selectedSegment: CarSegment? = null
    private val cardMap = mutableMapOf<CarSegment, MaterialCardView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 맨 처음 차종 선택 화면에서는 플로팅 위젯이 뜨지 않도록 기존 코칭 서비스 확실히 정지
        stopService(Intent(this, CoachOverlayService::class.java))

        val app = application as RentalCoachApplication
        if (savedInstanceState == null) app.beginRendyTtsSession()
        userPreferences = app.userPreferences

        // 시뮬레이터 및 시연 환경: 앱이 켜질 때마다 항상 '시동 직후 차종 선택' 화면이 나타나도록 보장
        binding = ActivityStartupCarSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initCardMappings()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        // 차종 선택 화면 체류 중에는 플로팅 버튼 미노출 보장
        stopService(Intent(this, CoachOverlayService::class.java))
    }

    private fun initCardMappings() {
        cardMap[CarSegment.COMPACT] = binding.cardCompact
        cardMap[CarSegment.SEDAN] = binding.cardSedan
        cardMap[CarSegment.SUV] = binding.cardSuv
        cardMap[CarSegment.VAN] = binding.cardVan
        cardMap[CarSegment.TRUCK] = binding.cardTruck
        cardMap[CarSegment.NONE] = binding.cardNone

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
                navigateToReturnInfo()
            }
        }

        binding.btnSkip.setOnClickListener {
            if (!userPreferences.isPresetConfigured()) {
                userPreferences.saveUsualCarSegment(CarSegment.SEDAN)
            }
            navigateToReturnInfo()
        }
    }

    private fun selectSegment(segment: CarSegment) {
        selectedSegment = segment

        val activeStroke = ContextCompat.getColor(this, R.color.hyundai_accent)
        val defaultStroke = ContextCompat.getColor(this, R.color.car_surface_light_stroke)
        val selectedCardBg = ContextCompat.getColor(this, R.color.car_card_selected)
        val defaultCardBg = ContextCompat.getColor(this, R.color.car_surface_light)

        cardMap.forEach { (seg, card) ->
            if (seg == segment) {
                card.strokeColor = activeStroke
                card.strokeWidth = 5
                card.setCardBackgroundColor(selectedCardBg)
                card.cardElevation = 14f
            } else {
                card.strokeColor = defaultStroke
                card.strokeWidth = 2
                card.setCardBackgroundColor(defaultCardBg)
                card.cardElevation = 4f
            }
        }

        binding.btnConfirm.isEnabled = true
        binding.btnConfirm.alpha = 1.0f
    }

    /**
     * 차종 선택 이후 반납지 및 반납 일시 입력 화면으로 이동
     */
    private fun navigateToReturnInfo() {
        startActivity(Intent(this, ReturnInfoActivity::class.java))
        finish()
    }
}
