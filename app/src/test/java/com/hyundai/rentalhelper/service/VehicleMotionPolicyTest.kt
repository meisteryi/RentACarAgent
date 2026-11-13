package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.intent.DrivingState
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleMotionPolicyTest {
    private fun state(speed: Float?, gear: String?) = VehicleMotionPolicy.evaluate(speed, gear, MotionSource.REAL).state

    @Test
    fun parkedNeedsBothZeroSpeedAndPark() {
        assertEquals(DrivingState.PARKED, state(0f, "PARK"))
        assertEquals(DrivingState.PARKED, state(0.5f, "park"))
        // P alone or 0 km/h alone is not enough.
        assertEquals(DrivingState.UNKNOWN, state(null, "PARK"))
        assertEquals(DrivingState.UNKNOWN, state(0f, null))
        // Stopped in D (traffic light) is not parked.
        assertEquals(DrivingState.DRIVING, state(0f, "DRIVE"))
        assertEquals(DrivingState.DRIVING, state(0f, "NEUTRAL"))
        assertEquals(DrivingState.DRIVING, state(0f, "REVERSE"))
    }

    @Test
    fun movingIsDrivingWhateverTheGear() {
        assertEquals(DrivingState.DRIVING, state(60f, "PARK"))
        assertEquals(DrivingState.DRIVING, state(3f, null))
    }

    @Test
    fun noSignalIsUnknownNeverParked() {
        val m = VehicleMotionPolicy.evaluate(null, null, MotionSource.REAL)
        assertEquals(DrivingState.UNKNOWN, m.state)
        assertEquals(MotionSource.NONE, m.source)
        assertEquals(DrivingState.UNKNOWN, state(Float.NaN, "PARK"))
        assertEquals(DrivingState.UNKNOWN, state(0f, "UNKNOWN"))
    }

    @Test
    fun demoSourceIsLabelled() {
        assertEquals(MotionSource.DEMO, VehicleMotionPolicy.evaluate(0f, "PARK", MotionSource.DEMO).source)
    }
}
