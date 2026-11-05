package com.hyundai.rentalhelper.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 평소 운전하는 차종(프리셋) 저장 및 관리
 */
enum class CarSegment(val code: String, val displayName: String, val description: String) {
    COMPACT("COMPACT", "경차", "캐스퍼, 모닝, 레이 등 경형 차량"),
    SEDAN("SEDAN", "세단", "아반떼, 쏘나타, 그랜저 등 일반 승용차"),
    SUV("SUV", "SUV", "투싼, 싼타페, 팰리세이드 등 다목적 차량"),
    VAN("VAN", "승합차", "스타리아, 카니발 등 대형 패밀리/승합차"),
    TRUCK("TRUCK", "트럭/상용차", "포터, 봉고 등 화물 상용차");

    companion object {
        fun fromCode(code: String?): CarSegment? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "rental_car_adaptation_prefs"
        private const val KEY_USUAL_CAR_SEGMENT = "usual_car_segment"
        private const val KEY_FIRST_RUN = "is_first_run"
    }

    /**
     * 평소 주로 타는 차종 저장
     */
    fun saveUsualCarSegment(segment: CarSegment) {
        prefs.edit()
            .putString(KEY_USUAL_CAR_SEGMENT, segment.code)
            .putBoolean(KEY_FIRST_RUN, false)
            .apply()
    }

    /**
     * 저장된 평소 차종 반환 (없으면 null)
     */
    fun getUsualCarSegment(): CarSegment? {
        val code = prefs.getString(KEY_USUAL_CAR_SEGMENT, null)
        return CarSegment.fromCode(code)
    }

    /**
     * 프리셋 설정 완료 여부
     */
    fun isPresetConfigured(): Boolean {
        return prefs.contains(KEY_USUAL_CAR_SEGMENT)
    }

    /**
     * 프리셋 초기화 (테스트용)
     */
    fun clearPreset() {
        prefs.edit().clear().apply()
    }
}

