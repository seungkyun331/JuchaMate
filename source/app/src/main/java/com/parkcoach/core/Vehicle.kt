package com.parkcoach.core

import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Everything the model needs to know about one car. Lengths in metres.
 * [steeringRatio] is steering-wheel degrees per degree of front-wheel angle, and
 * [lockDeg] is how far the steering wheel turns to one side.
 */
data class VehicleSpec(
    val id: String,
    val name: String,
    val length: Double,
    val width: Double,
    val wheelbase: Double,
    val rearOverhang: Double,
    val steeringRatio: Double,
    val lockDeg: Double,
) {
    val maxRoad: Double get() = Math.toRadians(lockDeg / steeringRatio)
    val minRadius: Double get() = wheelbase / tan(maxRoad)

    /** How far the middle of the body sits ahead of the rear axle. */
    val centerAhead: Double get() = length / 2 - rearOverhang

    fun body(p: Pose): Box = Box(p.toWorld(Vec(centerAhead, 0.0)), p.h, length, width)

    /** The rear-axle pose that puts the middle of the body at [center]. */
    fun axleAt(center: Vec, h: Double): Pose {
        val f = Pose(center.x, center.y, h)
        val p = f.toWorld(Vec(-centerAhead, 0.0))
        return Pose(p.x, p.y, h)
    }

    /** Steering-wheel degrees (right positive, as the car reports them) to front-wheel radians (left positive). */
    fun road(wheelDegRight: Double): Double = -Math.toRadians(wheelDegRight / steeringRatio)

    fun wheelDeg(road: Double): Double = -Math.toDegrees(road) * steeringRatio

    /**
     * The same car with no more lock than the wheel really has. A lock worked out from a turning
     * radius, or picked by hand, can be more than the car's steering reaches (the Pleos emulator:
     * 656° from its dimensions, while its wheel stops at 500°), and then a plan would ask for a
     * turn of the wheel that cannot be made.
     */
    fun limitedTo(wheelLimit: Double?): VehicleSpec =
        if (wheelLimit != null && wheelLimit > 0.0 && wheelLimit < lockDeg) copy(lockDeg = wheelLimit) else this
}

/** Size classes with typical figures. They are starting points; the car's own numbers replace them when it reports any. */
object Vehicles {
    val presets: List<VehicleSpec> = listOf(
        VehicleSpec("small", "경차", 3.60, 1.60, 2.40, 0.55, 15.5, 540.0),
        VehicleSpec("compact", "준중형 세단", 4.70, 1.82, 2.72, 1.05, 14.5, 500.0),
        VehicleSpec("midsuv", "중형 SUV", 4.65, 1.87, 2.76, 0.98, 14.8, 500.0),
        VehicleSpec("ev", "전기 크로스오버", 4.65, 1.89, 3.00, 0.80, 14.5, 500.0),
        VehicleSpec("largesuv", "대형 SUV", 5.00, 1.98, 2.90, 1.10, 15.5, 520.0),
        VehicleSpec("van", "승합·밴", 5.25, 2.00, 3.27, 1.05, 16.0, 540.0),
    )

    val default: VehicleSpec get() = presets[2]

    fun byId(id: String?): VehicleSpec? = presets.firstOrNull { it.id == id }

    /** The preset closest in length, for when only the size is known. */
    fun closest(length: Double): VehicleSpec = presets.minByOrNull { kotlin.math.abs(it.length - length) } ?: default

    /**
     * Builds a spec from what Android Automotive reports about the car (all in millimetres).
     * [turningRadiusMm] is the kerb-to-kerb turning radius; zero means unknown.
     */
    fun fromDimensions(name: String, lengthMm: Int, widthMm: Int, wheelbaseMm: Int, turningRadiusMm: Int): VehicleSpec? {
        val length = lengthMm / 1000.0
        val width = widthMm / 1000.0
        val wheelbase = wheelbaseMm / 1000.0
        if (length < 2.5 || length > 7.0 || width < 1.2 || width > 2.6 || wheelbase < 1.6 || wheelbase >= length) return null
        val like = closest(length)
        val rear = (length - wheelbase) * 0.52
        var lock = like.lockDeg
        // A car turns kerb to kerb in a radius of about two wheelbases. A figure well beyond that is
        // the turning circle's diameter given in the radius's place, as the Pleos emulator does (10.4 m
        // for a 3 m wheelbase), and taken at face value it would be a car that can hardly turn at all.
        val reported = turningRadiusMm / 1000.0
        val turning = if (reported > wheelbase * 2.6) reported / 2 else reported
        if (turning > wheelbase * 1.4) {
            // The outer front wheel runs on the kerb-to-kerb circle; the rear axle middle runs inside it.
            val rearRadius = sqrt(turning * turning - wheelbase * wheelbase) - width / 2
            if (rearRadius > 2.0) lock = Math.toDegrees(atan(wheelbase / rearRadius)) * like.steeringRatio
        }
        return VehicleSpec("car", name.ifBlank { "내 차" }, length, width, wheelbase, rear, like.steeringRatio, lock)
    }
}
