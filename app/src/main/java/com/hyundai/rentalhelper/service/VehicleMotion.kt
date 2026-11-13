package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.intent.DrivingState

/** Where a motion decision came from. DEMO is never mixed with REAL values. */
enum class MotionSource { NONE, REAL, DEMO }

data class VehicleMotion(val state: DrivingState, val source: MotionSource, val detail: String)

/**
 * Driving/parked decision for the voice guidance policy, from AAOS vehicle properties (PERF_VEHICLE_SPEED,
 * GEAR_SELECTION). No Pleos vehicle API is available in this project (only Pleos TTS/STT), so these are the
 * supported signals.
 *  - PARKED only when BOTH are known: speed <= [STOPPED_KMH] AND gear is PARK. P alone is not enough
 *    (e.g. a stale/unsupported speed), and speed 0 in D (traffic light) is not parked.
 *  - DRIVING when speed > [STOPPED_KMH] or a non-P gear is known.
 *  - UNKNOWN otherwise (nothing received yet, or only one signal). Callers treat UNKNOWN like "maybe driving".
 */
object VehicleMotionPolicy {
    const val STOPPED_KMH = 1.0f

    fun evaluate(speedKmh: Float?, gear: String?, source: MotionSource): VehicleMotion {
        val g = gear?.uppercase()
        val detail = "speed=${speedKmh ?: "?"},gear=${g ?: "?"},source=$source"
        val speedKnown = speedKmh != null && speedKmh.isFinite() && speedKmh >= 0f
        val state = when {
            speedKnown && speedKmh!! > STOPPED_KMH -> DrivingState.DRIVING
            g != null && g != "PARK" && g != "UNKNOWN" -> DrivingState.DRIVING
            speedKnown && g == "PARK" -> DrivingState.PARKED
            else -> DrivingState.UNKNOWN
        }
        return VehicleMotion(state, if (speedKmh == null && g == null) MotionSource.NONE else source, detail)
    }
}
