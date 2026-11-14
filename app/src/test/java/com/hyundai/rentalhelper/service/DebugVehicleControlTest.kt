package com.hyundai.rentalhelper.service

import com.hyundai.rentalhelper.intent.DrivingState
import com.hyundai.rentalhelper.service.DebugVehicleControl.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug gear/speed panel rules, fed through the unchanged safety policy (DEMO source). */
class DebugVehicleControlTest {
    private fun decide(g: Gear, kmh: Int) = VehicleMotionPolicy.evaluate(kmh.toFloat(), g.signal, MotionSource.DEMO)

    @Test
    fun safetyTableFromTheSpec() {
        // gear / speed -> detailed guidance allowed only when PARKED
        assertEquals(DrivingState.PARKED, decide(Gear.P, 0).state)
        assertEquals(DrivingState.DRIVING, decide(Gear.D, 0).state)
        assertEquals(DrivingState.DRIVING, decide(Gear.D, 40).state)
        assertEquals(DrivingState.DRIVING, decide(Gear.N, 0).state)
        assertEquals(DrivingState.DRIVING, decide(Gear.R, 5).state)
        assertEquals(DrivingState.UNKNOWN, VehicleMotionPolicy.evaluate(null, null, MotionSource.NONE).state)
        assertTrue(decide(Gear.D, 40).detail.contains("source=DEMO"))
    }

    @Test
    fun gearRulesForTheSlider() {
        assertFalse("P: slider disabled", DebugVehicleControl.speedAdjustable(Gear.P))
        assertEquals("P forces 0", 0, DebugVehicleControl.clampSpeed(Gear.P, 40))
        assertEquals("N keeps its speed (coasting)", 30, DebugVehicleControl.clampSpeed(Gear.N, 30))
        assertEquals("D independent of speed", 0, DebugVehicleControl.clampSpeed(Gear.D, 0))
        assertEquals(40, DebugVehicleControl.clampSpeed(Gear.D, 40))
        assertEquals(DebugVehicleControl.MAX_FORWARD_KMH, DebugVehicleControl.clampSpeed(Gear.D, 500))
        assertEquals("R uses the reverse range", DebugVehicleControl.MAX_REVERSE_KMH, DebugVehicleControl.clampSpeed(Gear.R, 60))
        assertEquals(120, DebugVehicleControl.maxSpeed(Gear.N))
    }

    @Test
    fun presets() {
        val p = DebugVehicleControl.PRESETS.associate { it.label to (it.gear to it.speedKmh) }
        assertEquals(Gear.P to 0, p["주차"])
        assertEquals(Gear.D to 0, p["신호 대기"])
        assertEquals(Gear.D to 40, p["주행"])
        assertEquals(Gear.R to 5, p["후진"])
        DebugVehicleControl.PRESETS.forEach { assertEquals(it.speedKmh, DebugVehicleControl.clampSpeed(it.gear, it.speedKmh)) }
        assertEquals(listOf("PARK", "REVERSE", "NEUTRAL", "DRIVE"), Gear.entries.map { it.signal })
    }
}
