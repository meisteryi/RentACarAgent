package com.hyundai.rentalhelper.service

/**
 * Rules of the debug-only gear/speed panel (DEMO vehicle signal). It only produces DEMO values for
 * [VehicleSdkManager.setDemoMotion]; the parked/driving decision stays in [VehicleMotionPolicy]
 * (PARKED only for gear P and speed <= 1 km/h; speed 0 alone is never "parked").
 */
object DebugVehicleControl {
    /** Gear values are the same strings the real GEAR_SELECTION mapping uses. */
    enum class Gear(val label: String, val signal: String) {
        P("P", "PARK"), R("R", "REVERSE"), N("N", "NEUTRAL"), D("D", "DRIVE")
    }

    const val MAX_FORWARD_KMH = 120
    /** Reversing range: walking pace to a quick reverse. */
    const val MAX_REVERSE_KMH = 20

    data class Preset(val label: String, val gear: Gear, val speedKmh: Int)

    val PRESETS = listOf(
        Preset("주차", Gear.P, 0),
        Preset("신호 대기", Gear.D, 0),
        Preset("주행", Gear.D, 40),
        Preset("후진", Gear.R, 5)
    )

    fun maxSpeed(gear: Gear): Int = when (gear) {
        Gear.P -> 0
        Gear.R -> MAX_REVERSE_KMH
        Gear.N, Gear.D -> MAX_FORWARD_KMH
    }

    /** P forces 0 km/h (slider disabled); N is not forced to 0 (coasting in neutral is possible). */
    fun speedAdjustable(gear: Gear): Boolean = gear != Gear.P

    fun clampSpeed(gear: Gear, speedKmh: Int): Int = speedKmh.coerceIn(0, maxSpeed(gear))
}
