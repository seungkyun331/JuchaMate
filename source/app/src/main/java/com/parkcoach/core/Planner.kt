package com.parkcoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One move: forward (+1) or reverse (-1), front wheels held at [road] radians (left positive), for [dist] metres. */
data class Seg(val dir: Int, val road: Double, val dist: Double)

/**
 * Works out how to get the car into the space from where it stands.
 *
 * The last part of any parking move has an exact answer: from a given position there is one circle
 * that swings the car onto the centre line of the space ([connect]). When that circle is tighter than
 * the car can turn, or something is in the way, the planner first tries short repositioning moves and
 * keeps the cheapest one from which the exact answer works ([plan]).
 */
class Planner(
    private val spec: VehicleSpec,
    private val lot: Lot,
    private val margin: Double = 0.12,
    /** Somewhere else the car must not be taken, asked afresh each time because it can move. */
    private val keepOut: () -> Box? = { null },
) {
    private val target = lot.target
    private val parallel = lot.config.type == SlotType.PARALLEL

    /** True when the car goes in nose-first, so the last swing is driven forward. */
    private val noseFirst = lot.config.type == SlotType.NOSE_IN || lot.config.type == SlotType.ANGLED
    private val into = if (noseFirst) 1 else -1

    /** +1 for a place laid out the usual way round, -1 for its mirror image ([LotConfig.mirror]). */
    private val hand = if (lot.config.mirrored) -1 else 1

    fun free(p: Pose, m: Double = margin): Boolean {
        val body = spec.body(p).grow(m)
        if (keepOut()?.intersects(body) == true) return false
        return lot.obstacles.none { it.box.intersects(body) }
    }

    /** The poses along a list of moves, or null if the car would touch something. */
    fun rollout(start: Pose, segs: List<Seg>, m: Double = margin): List<Pose>? {
        if (!free(start, m)) return null
        val out = ArrayList<Pose>()
        out.add(start)
        var p = start
        for (s in segs) {
            var left = s.dist
            while (left > 1e-6) {
                val d = min(STEP, left)
                p = Motion.advance(p, s.dir * d, s.road, spec.wheelbase)
                if (!free(p, m)) return null
                out.add(p)
                left -= d
            }
        }
        return out
    }

    /**
     * Whether a car standing at [p] counts as parked: what the guide accepts before it asks for P.
     * Every plan has to end like this, or following it to the letter would still leave the car out.
     */
    fun parks(p: Pose): Boolean {
        val rel = target.relative(p)
        return abs(rel.h) < Math.toRadians(PARKED_DEG) && abs(rel.x) < PARKED_ALONG && lot.holds(spec.body(p), PARKED_SLACK)
    }

    /** The moves, if they touch nothing and end parked. */
    private fun works(p: Pose, segs: List<Seg>, m: Double): Boolean = rollout(p, segs, m)?.let { parks(it.last()) } == true

    /** The exact final moves from [p], ignoring obstacles; null when no single swing does it. */
    fun connect(p: Pose): List<Seg>? = if (parallel) connectParallel(p, 0.0, TIGHTEST) else connectArc(p, into, false, TIGHTEST)

    /**
     * One reversing arc onto the centre line, then a straight roll to the mark. A space entered from
     * the side can also be finished by rolling forward, so [forwardToo] allows the straight part either way.
     */
    private fun connectArc(p: Pose, dir: Int, forwardToo: Boolean, tightest: Double): List<Seg>? {
        val r = target.relative(p)
        if (abs(r.y) < 0.10 && abs(r.h) < Math.toRadians(2.5)) {
            if (dir < 0 && r.x < -0.6 && !forwardToo) return null
            if (dir > 0 && r.x > 0.6) return null
            return listOf(if (r.x >= 0) Seg(-1, 0.0, r.x) else Seg(1, 0.0, -r.x))
        }
        val denom = 1 - cos(r.h)
        if (denom < 1e-4) return null
        // Signed radius of the one circle that ends on the centre line with the car straight.
        val signed = r.y / denom
        val side = if (signed >= 0) 1 else -1
        val radius = abs(signed)
        // Reversing on a left-hand circle turns the nose right; driving forward on it turns the nose left.
        if (r.h * side * dir >= 0 || abs(r.h) > Math.toRadians(135.0)) return null
        if (radius < spec.minRadius * tightest || radius > 60.0) return null
        val straight = r.x - signed * sin(r.h)
        if (dir < 0 && straight < -0.05 && !forwardToo) return null
        if (dir > 0 && straight > 0.05) return null
        val segs = ArrayList<Seg>()
        segs.add(Seg(dir, side * atan(spec.wheelbase / radius), radius * abs(r.h)))
        if (straight > 0.02) segs.add(Seg(-1, 0.0, straight))
        if (straight < -0.02) segs.add(Seg(1, 0.0, -straight))
        return segs
    }

    /**
     * [back] aims the swing at a point that far behind the perfect position, followed by a short
     * roll forward. In a short space that is what lets the nose clear the car in front.
     */
    private fun connectParallel(p: Pose, back: Double, tightest: Double): List<Seg>? {
        val r = target.relative(p)
        if (abs(r.h) > Math.toRadians(5.0)) return null
        if (abs(r.y) < 0.10) return listOf(Seg(if (r.x > 0) -1 else 1, 0.0, abs(r.x)))
        val ahead = r.x + back
        // Normally the car is out in the lane (to the left); the mirror image handles being too far to the kerb side.
        val side = if (r.y > 0) 1 else -1
        val d = abs(r.y)
        if (ahead < 0.5) return null
        // Two equal arcs with opposite lock make an S that moves the car sideways by d.
        val alpha = 2 * atan(d / ahead)
        if (alpha > Math.toRadians(65.0)) return null
        val radius = ahead / (2 * sin(alpha))
        if (radius < spec.minRadius * tightest || radius > 80.0) return null
        val road = atan(spec.wheelbase / radius)
        val segs = ArrayList<Seg>()
        segs.add(Seg(-1, -side * road, radius * alpha))
        segs.add(Seg(-1, side * road, radius * alpha))
        if (back > 0.02) segs.add(Seg(1, 0.0, back))
        return segs
    }

    /**
     * The exact final moves from [p] if they exist and touch nothing. [relaxed] is for a car already
     * part-way through a plan: it may use the last of the steering lock and pass a little closer.
     */
    fun direct(p: Pose, relaxed: Boolean = false): List<Seg>? {
        val m = if (relaxed) RELAXED_MARGIN else margin
        val tightest = if (relaxed) 1.0 else TIGHTEST
        if (!parallel) {
            val segs = connectArc(p, into, false, tightest) ?: return null
            return if (works(p, segs, m)) segs else null
        }
        if (abs(target.relative(p).h) > Math.toRadians(5.0)) {
            // Part-way through the S: one more arc brings the car parallel to the kerb.
            val segs = connectArc(p, -1, true, tightest) ?: return null
            return if (works(p, segs, m)) segs else null
        }
        var back = 0.0
        while (back <= 2.0) {
            val segs = connectParallel(p, back, tightest)
            if (segs != null && works(p, segs, m)) return segs
            back += 0.2
        }
        return null
    }

    private class Node(val pose: Pose, val g: Double, val f: Double, val parent: Node?, val dir: Int, val road: Double)

    /**
     * All the moves from [p] into the space, or null if none was found.
     * When the exact swing does not work from here, this searches outward through short moves
     * (forward or back; straight, hard left or hard right) for the cheapest position from which it does.
     * Changing direction costs extra, so it prefers the plan with the fewest back-and-forths.
     * [fresh] skips the exact swing from [p] itself, for when that swing is too short to ask for.
     */
    fun plan(p: Pose, fresh: Boolean = false): List<Seg>? {
        if (!fresh) direct(p)?.let { return it }
        // In a tight space a car can stand closer to the next one than the margin and still drive
        // away from it: the search then starts with the smaller margin a car part-way through uses.
        val m = if (free(p)) margin else RELAXED_MARGIN
        if (!free(p, m)) return null
        val lock = spec.maxRoad * 0.92
        val roads = listOf(0.0, lock, -lock)
        val open = java.util.PriorityQueue<Node>(64) { x, y -> x.f.compareTo(y.f) }
        val seen = HashSet<Long>()
        open.add(Node(p, 0.0, 0.0, null, 0, 0.0))
        var expanded = 0
        while (open.isNotEmpty() && expanded < SEARCH_LIMIT) {
            val n = open.poll() ?: break
            if (!seen.add(cell(n.pose))) continue
            expanded++
            if (n.parent != null) {
                val tail = direct(n.pose)
                if (tail != null) return merged(n) + tail
            }
            for (dir in listOf(1, -1)) {
                for (road in roads) {
                    var q = n.pose
                    var clear = true
                    for (i in 0 until HOP_STEPS) {
                        q = Motion.advance(q, dir * STEP, road, spec.wheelbase)
                        if (!free(q, m)) {
                            clear = false
                            break
                        }
                    }
                    if (!clear || (q.pos - target.pos).len() > 30.0) continue
                    var cost = HOP_STEPS * STEP
                    if (n.dir != 0 && dir != n.dir) cost += 3.0
                    if (n.dir != 0 && road != n.road) cost += 0.4
                    val g = n.g + cost
                    open.add(Node(q, g, g + (q.pos - target.pos).len(), n, dir, road))
                }
            }
        }
        return null
    }

    private fun cell(p: Pose): Long {
        val x = Math.round(p.x / 0.3) + 2000
        val y = Math.round(p.y / 0.3) + 2000
        val h = Math.round(Math.toDegrees(wrap(p.h)) / 10.0) + 18
        return (x * 4096 + y) * 64 + h
    }

    private fun merged(end: Node): List<Seg> {
        val hops = ArrayList<Node>()
        var n: Node? = end
        while (n != null && n.parent != null) {
            hops.add(n)
            n = n.parent
        }
        hops.reverse()
        val out = ArrayList<Seg>()
        for (hop in hops) {
            val lastSeg = out.lastOrNull()
            if (lastSeg != null && lastSeg.dir == hop.dir && lastSeg.road == hop.road) {
                out[out.size - 1] = lastSeg.copy(dist = lastSeg.dist + HOP_STEPS * STEP)
            } else {
                out.add(Seg(hop.dir, hop.road, HOP_STEPS * STEP))
            }
        }
        return out
    }

    /**
     * A good place to stop before reversing in: the green box. [toLeft] says which way along the row
     * the car is travelling, seen from the parked position. Null when no one-swing position exists.
     */
    fun setup(toLeft: Boolean): Pose? {
        if (parallel) {
            val d = spec.width + 0.9
            var back = 0.0
            while (back <= 2.0) {
                for (k in listOf(1.2, 1.08, 1.4)) {
                    val radius = spec.minRadius * k
                    val alpha = acos((1 - d / (2 * radius)).coerceIn(-1.0, 1.0))
                    // Out in the road, which is on the car's left unless the place is the other way round.
                    val pose = target.compose(Pose(2 * radius * sin(alpha) - back, hand * d, 0.0))
                    if (direct(pose) != null) return pose
                }
                back += 0.2
            }
            return null
        }
        val first = if (toLeft) 1 else -1
        if (noseFirst) {
            // Nose-first: the same swing as leaving in reverse, played backwards. In a slanted row the
            // car can only come from the direction the bays lean toward.
            val slanted = lot.config.type == SlotType.ANGLED
            val turn = Math.toRadians(if (slanted) lot.config.angle.coerceIn(40.0, 80.0) else 90.0)
            for (side in if (slanted) listOf(hand) else listOf(first, -first)) {
                for (k in listOf(1.25, 1.1, 1.5, 1.8)) {
                    val radius = spec.minRadius * k
                    var a = 0.3
                    while (a <= 4.0) {
                        val pose = target.compose(Pose(-a - radius * sin(turn), side * radius * (1 - cos(turn)), -side * turn))
                        if (direct(pose) != null) return pose
                        a += 0.25
                    }
                }
            }
            return null
        }
        for (side in listOf(first, -first)) {
            for (k in listOf(1.25, 1.1, 1.5, 1.8)) {
                val radius = spec.minRadius * k
                var a = 0.3
                while (a <= 4.0) {
                    val pose = target.compose(Pose(radius + a, side * radius, side * PI / 2))
                    if (direct(pose) != null) return pose
                    a += 0.25
                }
            }
        }
        // No one-swing position: offer a place in the lane from which a few moves do it.
        val lane = if (lot.config.opposite == Opposite.OPEN) 5.5 else lot.config.aisle
        val out = spec.centerAhead + lot.slot.length / 2 + lane / 2
        for (side in listOf(first, -first)) {
            for (along in listOf(4.0, 3.0, 5.0, 2.0)) {
                val pose = target.compose(Pose(out, side * along, side * PI / 2))
                if (free(pose) && plan(pose) != null) return pose
            }
        }
        return null
    }

    /** The way out of the space that ends at [setup]: the parking moves played backwards. */
    fun exit(toLeft: Boolean): List<Seg>? {
        val pose = setup(toLeft) ?: return null
        val segs = direct(pose) ?: return null
        return segs.reversed().map { Seg(-it.dir, it.road, it.dist) }
    }

    /**
     * A way out that ends somewhere new every time: the tidy exit, then a few metres more on a
     * slight curve. Practice should never start from the spot the last attempt ended at.
     */
    fun wander(toLeft: Boolean, random: kotlin.random.Random = kotlin.random.Random.Default): List<Seg>? {
        val out = exit(toLeft) ?: return null
        repeat(10) {
            val extra = Seg(1, (random.nextDouble() - 0.5) * 0.40, 1.5 + random.nextDouble() * 3.5)
            if (rollout(target, out + extra) != null) return out + extra
        }
        return out
    }

    companion object {
        const val STEP = 0.15
        const val HOP_STEPS = 3
        const val SEARCH_LIMIT = 6000

        /** Plans never ask for more than this share of full lock, so the driver has something in hand. */
        const val TIGHTEST = 1.03
        const val RELAXED_MARGIN = 0.04

        /** Parked: straight to within this many degrees, this far from the mark, this far over the lines. */
        const val PARKED_DEG = 8.0
        const val PARKED_ALONG = 0.3
        const val PARKED_SLACK = 0.12
    }
}
