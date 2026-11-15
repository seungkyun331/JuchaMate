package com.parkcoach.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A stand-in car: pedals, a steering wheel and a gear lever that respond over time the way real ones do.
 * It reports the same three things a real car does (speed, steering angle, gear) and nothing else.
 */
class SimCar(private val spec: VehicleSpec) {
    var gear: Gear = Gear.PARK
    var speed: Double = 0.0
        private set
    var wheelDeg: Double = 0.0
        private set
    var targetSpeed: Double = 0.0
    var targetWheel: Double = 0.0

    fun step(dt: Double) {
        val lock = spec.lockDeg
        val goal = targetWheel.coerceIn(-lock, lock)
        val turn = WHEEL_RATE * dt
        wheelDeg = if (abs(goal - wheelDeg) <= turn) goal else wheelDeg + turn * if (goal > wheelDeg) 1 else -1
        val want = if (gear == Gear.DRIVE || gear == Gear.REVERSE) max(targetSpeed, 0.0) else 0.0
        speed = if (want > speed) min(want, speed + ACCEL * dt) else max(want, speed - BRAKE * dt)
    }

    /** Does what a hint says, like a careful learner. */
    fun follow(hint: Hint?, cruise: Double = 1.2) {
        if (hint == null) {
            targetSpeed = 0.0
            return
        }
        when (hint.cue) {
            Cue.STEER -> {
                targetSpeed = 0.0
                targetWheel = hint.targetWheelDeg
            }
            Cue.SHIFT -> {
                targetSpeed = 0.0
                if (speed < 0.02) gear = if (hint.dir > 0) Gear.DRIVE else Gear.REVERSE
            }
            Cue.MOVE -> {
                targetWheel = hint.targetWheelDeg
                targetSpeed = when {
                    hint.remaining > 1.5 -> cruise
                    hint.remaining > 0.5 -> 0.6
                    else -> 0.3
                }
            }
            Cue.STOP, Cue.FIND_SPOT -> targetSpeed = 0.0
            Cue.PARK -> {
                targetSpeed = 0.0
                if (speed < 0.02) gear = Gear.PARK
            }
        }
    }

    companion object {
        const val WHEEL_RATE = 420.0
        const val ACCEL = 1.2
        const val BRAKE = 2.5
    }
}
