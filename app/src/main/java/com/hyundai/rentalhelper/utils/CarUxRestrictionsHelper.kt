package com.hyundai.rentalhelper.utils

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android Automotive OS (AAOS) 주행 규제 (CarUxRestrictions) 헬퍼
 * 운전자의 시선 분산(Driver Distraction)을 방지하기 위해 주행 여부를 감지하고
 * 텍스트 길이 제한, 터치 제한 등 규제 상태를 통지합니다.
 */
class CarUxRestrictionsHelper(
    private val context: Context,
    private val onRestrictionStateChanged: (isDrivingRestricted: Boolean, maxStringLength: Int) -> Unit
) {
    companion object {
        private const val TAG = "CarUxRestrictionsHelper"
    }

    private var car: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isSimulatedDriving = false

    private val restrictionListener =
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
            handleRestrictions(restrictions)
        }

    fun startListening() {
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { carInstance, ready ->
                if (ready) {
                    try {
                        uxRestrictionsManager =
                            carInstance.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        uxRestrictionsManager?.registerListener(restrictionListener)

                        val current = uxRestrictionsManager?.currentCarUxRestrictions
                        if (current != null) {
                            handleRestrictions(current)
                        } else {
                            notifyState(false, Int.MAX_VALUE)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CarUxRestrictionsManager 초기화 중 예외 (에뮬레이터 폴백 활성화)", e)
                        notifyState(false, Int.MAX_VALUE)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AAOS Car API 연결 불가. 로컬 에뮬레이터 모드로 동작합니다.", e)
            notifyState(false, Int.MAX_VALUE)
        }
    }

    private fun handleRestrictions(restrictions: CarUxRestrictions) {
        val isRestricted = restrictions.isRequiresDistractionOptimization
        val maxLen = if (isRestricted) {
            restrictions.maxRestrictedStringLength.takeIf { it > 0 } ?: 120
        } else {
            Int.MAX_VALUE
        }
        Log.d(TAG, "CarUxRestrictions 변경: isRestricted=$isRestricted, maxLen=$maxLen")
        notifyState(isRestricted, maxLen)
    }

    private fun notifyState(isRestricted: Boolean, maxLen: Int) {
        mainHandler.post {
            onRestrictionStateChanged(isRestricted || isSimulatedDriving, maxLen)
        }
    }

    /**
     * Pleos / AAOS 에뮬레이터에서 adb 명령이나 테스트 버튼으로 주행 상태 시뮬레이션 토글
     * (예: adb shell am broadcast -a com.hyundai.rentalhelper.SIMULATE_DRIVING --ez driving true)
     */
    fun setSimulatedDriving(driving: Boolean) {
        this.isSimulatedDriving = driving
        Log.i(TAG, "주행 규제 시뮬레이션 모드 적용: isDriving=$driving")
        notifyState(driving, if (driving) 120 else Int.MAX_VALUE)
    }

    fun stopListening() {
        try {
            uxRestrictionsManager?.unregisterListener()
            car?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "CarUxRestrictions unregister 실패", e)
        }
    }
}

