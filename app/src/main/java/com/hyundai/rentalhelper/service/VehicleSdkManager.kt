package com.hyundai.rentalhelper.service

import android.car.Car
import android.car.CarInfoManager
import android.car.VehicleAreaType
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

data class VehicleState(
    val modelId: String,
    val modelName: String,
    val speedKmh: Float,
    val gear: String,
    val isMoving: Boolean
)

/**
 * Pleos Connect SDK & AAOS Car API 통합 관리자
 * 차종 인식 (CarInfo / VIN), 속도, 기어 상태를 모니터링합니다.
 * 에뮬레이터 환경 및 adb 테스트를 위한 Mock 주입 기능 내장.
 */
class VehicleSdkManager(
    private val context: Context,
    private val onStateUpdated: (VehicleState) -> Unit
) {
    companion object {
        private const val TAG = "VehicleSdkManager"

        // ADB 시뮬레이션 브로드캐스트 액션
        const val ACTION_SET_CAR_MODEL = "com.hyundai.rentalhelper.SET_CAR_MODEL"
        const val ACTION_SET_CAR_SPEED = "com.hyundai.rentalhelper.SET_CAR_SPEED"
        const val ACTION_SET_CAR_GEAR = "com.hyundai.rentalhelper.SET_CAR_GEAR"

        const val EXTRA_MODEL = "model"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_GEAR = "gear"
    }

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var carInfoManager: CarInfoManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 차량 상태 캐시 (기본값: 현대 투싼 SUV)
    private var currentModelId: String = "TUCSON"
    private var currentModelName: String = "투싼"
    private var currentSpeedKmh: Float = 0.0f
    private var currentGear: String = "PARK"

    private val adbSimulationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SET_CAR_MODEL -> {
                    val model = intent.getStringExtra(EXTRA_MODEL) ?: "TUCSON"
                    setSimulatedCarModel(model)
                }
                ACTION_SET_CAR_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 0.0f)
                    setSimulatedSpeed(speed)
                }
                ACTION_SET_CAR_GEAR -> {
                    val gear = intent.getStringExtra(EXTRA_GEAR) ?: "PARK"
                    setSimulatedGear(gear)
                }
            }
        }
    }

    fun start() {
        registerAdbReceiver()
        connectCarService()
    }

    private fun registerAdbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SET_CAR_MODEL)
            addAction(ACTION_SET_CAR_SPEED)
            addAction(ACTION_SET_CAR_GEAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adbSimulationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(adbSimulationReceiver, filter)
        }
    }

    private fun connectCarService() {
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { carInstance, ready ->
                if (ready) {
                    try {
                        carPropertyManager =
                            carInstance.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        carInfoManager =
                            carInstance.getCarManager(Car.INFO_SERVICE) as? CarInfoManager

                        // 1. 차량 모델 감지 시도
                        detectCarModel()

                        // 2. 속도 & 기어 센서 리스너 등록
                        subscribeVehicleProperties()
                    } catch (e: Exception) {
                        Log.w(TAG, "Car Manager 초기화 예외 (에뮬레이터 기본값 사용)", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AAOS Car API 연결 불가. Pleos 에뮬레이터 Mock 모드로 유지됩니다.", e)
        }

        // 초기 상태 알림
        dispatchState()
    }

    private fun detectCarModel() {
        try {
            val model = carInfoManager?.model
            if (!model.isNullOrBlank()) {
                Log.i(TAG, "AAOS CarInfoManager 모델 감지 성공: $model")
                setSimulatedCarModel(model)
            }
        } catch (e: Exception) {
            Log.d(TAG, "CarInfoManager 모델 조회 미지원, 기본 모델 유지: $currentModelId")
        }
    }

    private fun subscribeVehicleProperties() {
        val propManager = carPropertyManager ?: return

        try {
            // 속도 프로퍼티 감지
            propManager.registerCallback(
                object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(event: CarPropertyValue<*>) {
                        val speedVal = (event.value as? Float) ?: 0.0f
                        currentSpeedKmh = speedVal * 3.6f // m/s -> km/h 변환
                        dispatchState()
                    }

                    override fun onErrorEvent(propId: Int, zone: Int) {
                        Log.e(TAG, "Speed property error: propId=$propId")
                    }
                },
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_NORMAL
            )

            // 기어 변속 감지
            propManager.registerCallback(
                object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(event: CarPropertyValue<*>) {
                        val gearInt = (event.value as? Int) ?: VehicleGear.GEAR_PARK
                        currentGear = when (gearInt) {
                            VehicleGear.GEAR_PARK -> "PARK"
                            VehicleGear.GEAR_REVERSE -> "REVERSE"
                            VehicleGear.GEAR_NEUTRAL -> "NEUTRAL"
                            VehicleGear.GEAR_DRIVE -> "DRIVE"
                            else -> "UNKNOWN"
                        }
                        dispatchState()
                    }

                    override fun onErrorEvent(propId: Int, zone: Int) {
                        Log.e(TAG, "Gear property error: propId=$propId")
                    }
                },
                VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyManager.SENSOR_RATE_NORMAL
            )
        } catch (e: Exception) {
            Log.w(TAG, "차량 프로퍼티 구독 실패 (에뮬레이터 제한)", e)
        }
    }

    fun setSimulatedCarModel(modelIdentifier: String) {
        val upper = modelIdentifier.uppercase()
        when {
            upper.contains("AVANTE") || upper.contains("아반떼") -> {
                currentModelId = "AVANTE"
                currentModelName = "아반떼"
            }
            upper.contains("SONATA") || upper.contains("쏘나타") -> {
                currentModelId = "SONATA"
                currentModelName = "쏘나타 디 엣지"
            }
            upper.contains("IONIQ") || upper.contains("아이오닉") -> {
                currentModelId = "IONIQ5"
                currentModelName = "아이오닉 5"
            }
            upper.contains("CASPER") || upper.contains("캐스퍼") -> {
                currentModelId = "CASPER"
                currentModelName = "캐스퍼"
            }
            else -> {
                currentModelId = "TUCSON"
                currentModelName = "투싼"
            }
        }
        Log.i(TAG, "현재 렌터카 모델 설정됨: $currentModelName ($currentModelId)")
        dispatchState()
    }

    fun setSimulatedSpeed(speedKmh: Float) {
        currentSpeedKmh = speedKmh
        dispatchState()
    }

    fun setSimulatedGear(gear: String) {
        currentGear = gear.uppercase()
        dispatchState()
    }

    fun getCurrentState(): VehicleState {
        return VehicleState(
            modelId = currentModelId,
            modelName = currentModelName,
            speedKmh = currentSpeedKmh,
            gear = currentGear,
            isMoving = currentSpeedKmh > 1.0f || currentGear == "DRIVE"
        )
    }

    private fun dispatchState() {
        val state = getCurrentState()
        mainHandler.post {
            onStateUpdated(state)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(adbSimulationReceiver)
        } catch (e: Exception) {
            // ignore
        }
        try {
            car?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Car service disconnect fail", e)
        }
    }
}

