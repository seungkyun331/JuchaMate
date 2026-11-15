package com.parkcoach.core

import kotlin.math.abs
import kotlin.math.atan

enum class Cue {
    /** No way in from here yet: drive to the green box. */
    FIND_SPOT,
    STOP,

    /** Standing still: turn the steering wheel. */
    STEER,

    /** Change gear to the one in [Hint.dir]. */
    SHIFT,
    MOVE,

    /** In the space: select Park. */
    PARK,
}

/** What the driver should do right now. */
data class Hint(
    val cue: Cue,
    /** +1 forward, -1 reverse. */
    val dir: Int = 0,
    /** Metres left in this move, or to the green box. */
    val remaining: Double = 0.0,
    /** Where the steering wheel should be, in degrees, right positive. */
    val targetWheelDeg: Double = 0.0,
    /** How much further to turn it; positive means to the right. */
    val turnDeg: Double = 0.0,
    /** The planned path of the rear axle, for drawing. */
    val path: List<Pose> = emptyList(),
    /** Where to stop, when the car should move somewhere first. */
    val box: Pose? = null,
)

/** The instruction for carrying out one move, shared by the live guide and by scripted drives. */
internal fun moveHint(
    seg: Seg,
    left: Double,
    targetRoad: Double,
    moving: Boolean,
    gear: Gear,
    wheelDeg: Double,
    spec: VehicleSpec,
    path: List<Pose>,
): Hint {
    val targetWheel = spec.wheelDeg(targetRoad)
    val turn = targetWheel - wheelDeg
    if (!moving && abs(turn) > Guide.STEER_TOLERANCE) {
        return Hint(Cue.STEER, seg.dir, left, targetWheel, turn, path)
    }
    val want = if (seg.dir > 0) Gear.DRIVE else Gear.REVERSE
    if (gear != want) return Hint(Cue.SHIFT, seg.dir, left, targetWheel, turn, path)
    return Hint(if (left <= Guide.STOP_WITHIN) Cue.STOP else Cue.MOVE, seg.dir, left, targetWheel, turn, path)
}

/**
 * Talks the driver into the space. It only ever asks for the first move of the current plan, and
 * makes a fresh plan every time the car comes to rest, so a move that was stopped early, run long
 * or steered badly is simply absorbed into the next instruction.
 */
class Guide(private val spec: VehicleSpec, private val planner: Planner, private val lot: Lot) {
    private var segs: List<Seg> = emptyList()
    private var path: List<Pose> = emptyList()
    private var done = 0.0
    private var movedSincePlan = Double.MAX_VALUE
    private var still = 0.0
    private var box: Pose? = null
    private var boxLeft: Boolean? = null

    /**
     * The green box has been looked for since the car last came to rest. Finding none can take the
     * planner half a second, and this runs on the main thread: looked for again every frame, it froze
     * the screen for as long as there was none.
     */
    private var boxTried = false

    /** The car goes in nose-first (+1) or backs in (-1). */
    private val into = if (lot.config.type == SlotType.NOSE_IN || lot.config.type == SlotType.ANGLED) 1 else -1

    /** True when the last straight run into the space may be steered by feedback (see [feedbackClear]). */
    private var feedback = false

    fun update(pose: Pose, ds: Double, dt: Double, speed: Double, gear: Gear, wheelDeg: Double): Hint {
        val moving = abs(speed) > 0.05
        still = if (moving) 0.0 else still + dt
        if (movedSincePlan < Double.MAX_VALUE) movedSincePlan += abs(ds)
        val current = segs.firstOrNull()
        if (current != null && ds * current.dir > 0) done += abs(ds)

        val rel = lot.target.relative(pose)
        // Good enough is good enough: inside the lines, straight, and not hanging out the front or back.
        if (!moving && planner.parks(pose)) return Hint(Cue.PARK)

        if (!moving && still >= SETTLE && movedSincePlan > 0.05) {
            // What is left of the plan we were following.
            val rest = when {
                current == null -> emptyList()
                current.dist - done <= 0.25 -> segs.drop(1)
                else -> listOf(current.copy(dist = current.dist - done)) + segs.drop(1)
            }
            // The exact swing always wins. Otherwise keep to the plan while it still works from here,
            // so two fresh plans can never send the car back and forth over the same ground. "Works"
            // means it still ends in the space: a leftover begun from somewhere else leads somewhere else.
            val exact = planner.direct(pose)
            val kept = if (rest.isEmpty()) null else planner.rollout(pose, rest, Planner.RELAXED_MARGIN)
            val chosen = when {
                exact != null -> exact
                kept != null && lot.holds(spec.body(kept.last()), KEEP_SLACK) -> rest
                else -> planner.direct(pose, relaxed = true) ?: planner.plan(pose) ?: emptyList()
            }
            // A move no longer than the stop tolerance cannot be asked for: its instruction reads
            // "stop", and the car would stand there for ever. Such a plan says the car is as good as
            // in, which it is not (it would have been told to select P), so look for a correction.
            val trimmed = chosen.dropWhile { it.dist <= STOP_WITHIN }
            val next = if (trimmed.isEmpty() && chosen.isNotEmpty()) {
                planner.plan(pose, fresh = true)?.dropWhile { it.dist <= STOP_WITHIN } ?: emptyList()
            } else {
                trimmed
            }
            segs = next
            path = planner.rollout(pose, next, 0.0) ?: emptyList()
            // From a new place there may be a green box where there was none.
            if (box == null) boxTried = false
            done = 0.0
            movedSincePlan = 0.0
            feedback = next.size == 1 && feedbackClear(pose)
        }

        val seg = segs.firstOrNull()
        if (seg == null) {
            val toLeft = rel.h >= 0
            if (!boxTried || boxLeft != toLeft) {
                box = planner.setup(toLeft)
                boxLeft = toLeft
                boxTried = true
            }
            val b = box
            val ahead = if (b != null) pose.toLocal(b.pos).x else 0.0
            val cue = if (b != null && moving && abs(ahead) < 0.3) Cue.STOP else Cue.FIND_SPOT
            return Hint(cue, if (ahead >= 0) 1 else -1, abs(ahead), box = b)
        }

        // The last straight run into a bay is steered by feedback, not by a fixed angle, but only
        // where that steering was found to bring the car in clear of everything. Otherwise the
        // straight line that was checked is what is asked for.
        val straightIn = feedback && segs.size == 1 && seg.road == 0.0 && lot.config.type != SlotType.PARALLEL && seg.dir == into
        // Signed: past the mark is less than nothing left, which says stop, not "keep going".
        val left = if (straightIn) -into * rel.x else seg.dist - done
        val road = if (!straightIn) seg.road else if (into > 0) noseIn(rel) else backIn(rel)
        return moveHint(seg, left, road, moving, gear, wheelDeg, spec, path)
    }

    /**
     * Whether steering by [noseIn] or [backIn] from [from] brings the car into the space without
     * touching anything. That steering is worked out afresh every frame, so it never went through
     * the planner: this runs it ahead once, the way the planner checks its own moves.
     */
    private fun feedbackClear(from: Pose): Boolean {
        val type = lot.config.type
        if (type == SlotType.PARALLEL) return false
        var p = from
        var steps = 0
        while (-into * lot.target.relative(p).x > STOP_WITHIN) {
            if (steps++ > FEEDBACK_STEPS) return false
            val rel = lot.target.relative(p)
            p = Motion.advance(p, into * Planner.STEP, if (into > 0) noseIn(rel) else backIn(rel), spec.wheelbase)
            if (!planner.free(p, Planner.RELAXED_MARGIN)) return false
        }
        // Roughly in is enough: the next stop straightens out whatever is left.
        return lot.holds(spec.body(p), KEEP_SLACK)
    }

    /** The same idea driving forward: point the nose at the centre line, then straighten. */
    private fun noseIn(rel: Pose): Double {
        val wantHeading = -atan(0.6 * rel.y)
        val road = atan(spec.wheelbase * 1.2 * (wantHeading - rel.h))
        return road.coerceIn(-spec.maxRoad * 0.9, spec.maxRoad * 0.9)
    }

    /** Front-wheel angle that brings a reversing car onto the centre line: aim the tail at the line, then straighten. */
    private fun backIn(rel: Pose): Double {
        val wantHeading = atan(0.6 * rel.y)
        val road = atan(-spec.wheelbase * 1.2 * (wantHeading - rel.h))
        return road.coerceIn(-spec.maxRoad * 0.9, spec.maxRoad * 0.9)
    }

    companion object {
        /** Steering-wheel degrees of error that are close enough while standing still. */
        const val STEER_TOLERANCE = 20.0
        const val STOP_WITHIN = 0.12
        const val SETTLE = 0.35

        /** How far outside the lines the end of a leftover plan may be and still be kept. */
        const val KEEP_SLACK = 0.3

        /** Steps of [Planner.STEP] a feedback run-in is followed ahead before it counts as going nowhere. */
        const val FEEDBACK_STEPS = 200
    }
}

/** Carries out a fixed list of moves without looking at where the car is. Used for the demo car's drive out. */
class Script(private val spec: VehicleSpec, segs: List<Seg>) {
    private val queue = ArrayDeque(segs)
    private var done = 0.0

    val finished: Boolean get() = queue.isEmpty()

    fun update(ds: Double, speed: Double, gear: Gear, wheelDeg: Double): Hint {
        val moving = abs(speed) > 0.05
        var seg = queue.firstOrNull() ?: return Hint(Cue.STOP)
        if (ds * seg.dir > 0) done += abs(ds)
        if (!moving && seg.dist - done <= Guide.STOP_WITHIN) {
            queue.removeFirst()
            done = 0.0
            seg = queue.firstOrNull() ?: return Hint(Cue.STOP)
        }
        return moveHint(seg, seg.dist - done, seg.road, moving, gear, wheelDeg, spec, emptyList())
    }
}
