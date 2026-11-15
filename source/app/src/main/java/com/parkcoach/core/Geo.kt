package com.parkcoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Metres on flat ground. x to the right, y up, angles counter-clockwise in radians. */
data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec(x * k, y * k)
    fun dot(o: Vec) = x * o.x + y * o.y
    fun len() = hypot(x, y)
}

fun wrap(a: Double): Double {
    var r = a % (2 * PI)
    if (r > PI) r -= 2 * PI
    if (r <= -PI) r += 2 * PI
    return r
}

/** A position with a heading. For a car it is the middle of the rear axle. */
data class Pose(val x: Double, val y: Double, val h: Double) {
    val pos: Vec get() = Vec(x, y)
    val fwd: Vec get() = Vec(cos(h), sin(h))
    val left: Vec get() = Vec(-sin(h), cos(h))

    /** A point given as (ahead, to the left) of this pose, in world coordinates. */
    fun toWorld(local: Vec): Vec = pos + fwd * local.x + left * local.y

    fun toLocal(world: Vec): Vec {
        val d = world - pos
        return Vec(d.dot(fwd), d.dot(left))
    }

    /** A pose given relative to this one, in world coordinates. */
    fun compose(local: Pose): Pose {
        val p = toWorld(local.pos)
        return Pose(p.x, p.y, wrap(h + local.h))
    }

    /** Another world pose as seen from this one: x ahead, y to the left, h relative heading. */
    fun relative(world: Pose): Pose {
        val p = toLocal(world.pos)
        return Pose(p.x, p.y, wrap(world.h - h))
    }
}

/** A rectangle on the ground: centre, heading, length along the heading and width across it. */
data class Box(val c: Vec, val h: Double, val length: Double, val width: Double) {
    private val frame: Pose get() = Pose(c.x, c.y, h)

    /** Front-left, front-right, rear-right, rear-left. */
    fun corners(): List<Vec> {
        val f = frame
        val l = length / 2
        val w = width / 2
        return listOf(f.toWorld(Vec(l, w)), f.toWorld(Vec(l, -w)), f.toWorld(Vec(-l, -w)), f.toWorld(Vec(-l, w)))
    }

    fun grow(m: Double) = Box(c, h, length + 2 * m, width + 2 * m)

    fun contains(p: Vec, slack: Double = 0.0): Boolean {
        val q = frame.toLocal(p)
        return abs(q.x) <= length / 2 + slack && abs(q.y) <= width / 2 + slack
    }

    /** Distance from a point to this rectangle; zero inside it. */
    fun gap(p: Vec): Double {
        val q = frame.toLocal(p)
        return hypot(maxOf(abs(q.x) - length / 2, 0.0), maxOf(abs(q.y) - width / 2, 0.0))
    }

    /** Corners and the middle of each side. */
    fun rim(): List<Vec> {
        val c = corners()
        return c + List(4) { (c[it] + c[(it + 1) % 4]) * 0.5 }
    }

    /** How far apart two rectangles are, to within a few centimetres; zero when they touch. */
    fun distance(o: Box): Double {
        if (intersects(o)) return 0.0
        return minOf(rim().minOf { o.gap(it) }, o.rim().minOf { gap(it) })
    }

    /** Separating-axis test for two rectangles. */
    fun intersects(o: Box): Boolean {
        if ((c - o.c).len() > (hypot(length, width) + hypot(o.length, o.width)) / 2) return false
        val a = corners()
        val b = o.corners()
        for (axis in listOf(frame.fwd, frame.left, o.frame.fwd, o.frame.left)) {
            var minA = Double.MAX_VALUE
            var maxA = -Double.MAX_VALUE
            var minB = Double.MAX_VALUE
            var maxB = -Double.MAX_VALUE
            for (p in a) {
                val d = p.dot(axis)
                if (d < minA) minA = d
                if (d > maxA) maxA = d
            }
            for (p in b) {
                val d = p.dot(axis)
                if (d < minB) minB = d
                if (d > maxB) maxB = d
            }
            if (maxA < minB || maxB < minA) return false
        }
        return true
    }
}

object Motion {
    /**
     * Where the rear axle ends up after rolling [ds] metres (negative in reverse) with the front wheels
     * turned by [road] radians (positive to the left). At parking speed tyres do not slip, so this
     * bicycle model is the whole physics: the car moves on a circle of radius wheelbase / tan(road).
     */
    fun advance(p: Pose, ds: Double, road: Double, wheelbase: Double): Pose {
        val k = tan(road) / wheelbase
        if (abs(k) < 1e-9) return Pose(p.x + ds * cos(p.h), p.y + ds * sin(p.h), p.h)
        val dh = ds * k
        return Pose(
            p.x + (sin(p.h + dh) - sin(p.h)) / k,
            p.y - (cos(p.h + dh) - cos(p.h)) / k,
            wrap(p.h + dh),
        )
    }
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
