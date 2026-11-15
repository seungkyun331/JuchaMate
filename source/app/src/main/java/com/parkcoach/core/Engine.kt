package com.parkcoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Phase {
    /** Nothing set up; the car is simply being tracked. */
    IDLE,

    /** Recording a first, unguided parking attempt. */
    RECORDING,

    /** A target space exists and the car stands in it. */
    READY,

    /** Driving out of the space. */
    LEAVING,

    /** Parking, with guidance. */
    PARKING,
    CRASHED,
    FINISHED,
}

/** Why a practice under way was given up, so the screen can say so. */
enum class Drop {
    /** The car went further from the space than any plan ever takes it: it is not practising here any more. */
    FAR,

    /** The steering angle or the speed was missing while the car was meant to be tracked. */
    BLIND,
}

data class Sample(val t: Double, val pose: Pose, val gear: Gear, val wheelDeg: Double)

/** How one drive went. A "move" is a stretch of driving in one direction. */
data class DriveStats(val moves: Int, val distance: Double, val seconds: Double) {
    companion object {
        fun of(samples: List<Sample>): DriveStats {
            var moves = 0
            var distance = 0.0
            var last = 0
            for (i in 1 until samples.size) {
                val a = samples[i - 1]
                val b = samples[i]
                val d = b.pose.pos - a.pose.pos
                val step = d.len()
                if (step < 0.02) continue
                distance += step
                val dir = if (d.dot(a.pose.fwd) >= 0) 1 else -1
                if (dir != last) moves++
                last = dir
            }
            val seconds = if (samples.size < 2) 0.0 else samples.last().t - samples.first().t
            return DriveStats(moves, distance, seconds)
        }
    }
}

data class Attempt(
    val success: Boolean,
    val crashed: Boolean,
    /** Metres from the perfect position, sideways. */
    val sideways: Double,
    /** Metres from the perfect position, along the space. */
    val lengthways: Double,
    val angleDeg: Double,
    val stats: DriveStats,
    val stars: Int,
    val samples: List<Sample>,
    /** With real cones out: the nearest the car's body came to each, in metres, left one first. */
    val cones: List<Double> = emptyList(),
)

/**
 * The whole trainer without a screen: tracks the car, keeps the virtual lot, runs the guide and
 * judges each attempt. It is stepped once per display frame with what the car reports.
 */
class Engine(spec: VehicleSpec) {
    var spec: VehicleSpec = spec
        private set
    var pose: Pose = Pose(0.0, 0.0, PI / 2)
        private set
    var gear: Gear = Gear.UNKNOWN
        private set

    /** Metres per second, negative in reverse. */
    var speed: Double = 0.0
        private set
    var wheelDeg: Double = 0.0
        private set
    var phase: Phase = Phase.IDLE
        private set
    var lot: Lot? = null
        private set
    var hint: Hint? = null
        private set
    var hit: Obstacle? = null
        private set
    var tooFast: Boolean = false
        private set

    /** Set by whoever reads the car: while a door is open no instruction is given. */
    var doorOpen: Boolean = false

    /** Set by whoever reads the car: the steering angle or the speed has not been read, so the path cannot be trusted. */
    var blind: Boolean = false

    /** Set when a practice was given up by itself; whoever shows it clears it. */
    var dropped: Drop? = null

    /**
     * False while an attempt that began touching something (straight after a crash) has not yet been
     * clear of it: until then a touch is the old one, not a new one.
     */
    private var armed = true

    /** Metres driven since this attempt began. Park before the car has moved at all ends nothing. */
    private var rolled = 0.0

    /**
     * True where there is something real around to run into: a car park. The app cannot see the far
     * side of the lane, so there it only ever guides the car over ground it has already been on.
     * False in an open space.
     */
    var bounded: Boolean = true

    /**
     * True while the demo car drives itself ("시범 보기"). The lane it drives in is drawn, not real,
     * so there is no unknown far side to keep it from; held to [limit] it often found no way back.
     */
    var selfDriven: Boolean = false

    /** Real cones stand at the front corners of the space: they are kept clear of, and measured against. */
    var cones: Boolean = false

    /** Metres beyond the mouth of the space that the car's body has actually been, in this place. */
    var proven: Double = 0.0
        private set

    /**
     * The far edge of where the car has been. Once there is one whole drive to go by, guidance keeps
     * the car this side of it; the driver going further out moves it, since that ground is then known
     * to be clear as well.
     */
    val limit: Box?
        get() {
            val place = lot ?: return null
            if (!bounded || selfDriven || (history.isEmpty() && baseline == null)) return null
            return place.strip(proven)
        }

    private val coneGaps = DoubleArray(2) { Double.MAX_VALUE }
    var baseline: List<Sample>? = null
        private set
    var last: Attempt? = null
        private set

    /** What the coach has to say about the last attempt, most important first. */
    var advice: List<Advice> = emptyList()
        private set

    /** Metres to the nearest virtual obstacle, like a parking sensor would report. */
    var clearance: Double = Double.MAX_VALUE
        private set
    val trail: ArrayList<Sample> = ArrayList()
    val history: ArrayList<Attempt> = ArrayList()

    private var planner: Planner? = null
    private var guide: Guide? = null
    private var clock = 0.0
    private var still = 0.0

    fun changeSpec(next: VehicleSpec) {
        spec = next
        val config = lot?.config
        if (config != null && (phase == Phase.READY || phase == Phase.FINISHED)) setTarget(config)
    }

    /** Puts the car somewhere else in the model. For tests, and for re-anchoring. */
    fun teleport(p: Pose) {
        pose = p
    }

    /**
     * One time step. [speedAbs] is metres per second without sign; the gear gives the direction.
     * [wheelDegRight] is the steering wheel angle, right positive.
     */
    fun step(dt: Double, speedAbs: Double, wheelDegRight: Double, g: Gear) {
        val sign = when (g) {
            Gear.DRIVE -> 1
            Gear.REVERSE -> -1
            else -> 0
        }
        val gearChanged = g != gear
        gear = g
        wheelDeg = wheelDegRight
        if (blind) {
            // Unknown is not zero: without the angle and the speed the path would be a guess, so
            // nothing moves and nothing is judged. A practice under way cannot be trusted after that.
            speed = 0.0
            tooFast = false
            hint = null
            val tracking = phase == Phase.LEAVING || phase == Phase.PARKING || phase == Phase.RECORDING
            if (tracking || (phase == Phase.READY && abs(speedAbs) > 0.05)) drop(Drop.BLIND)
            return
        }
        speed = abs(speedAbs) * sign
        tooFast = abs(speedAbs) > MAX_SPEED
        val ds = speed * dt
        if (ds != 0.0) pose = Motion.advance(pose, ds, spec.road(wheelDegRight), spec.wheelbase)
        clock += dt

        val tracking = phase == Phase.RECORDING || phase == Phase.LEAVING || phase == Phase.PARKING
        if (tracking) record(gearChanged)

        val place = lot
        val body = spec.body(pose)
        clearance = place?.clearance(body) ?: Double.MAX_VALUE
        if (place != null) {
            // Wherever the car is, the ground under it is clear.
            proven = max(proven, place.reach(body))
            if (phase == Phase.PARKING) {
                place.obstacles.filter { it.kind == Kind.CONE }.forEachIndexed { i, cone ->
                    if (i < coneGaps.size) coneGaps[i] = min(coneGaps[i], cone.box.distance(body))
                }
            }
        }
        when (phase) {
            Phase.RECORDING -> if (g == Gear.PARK && abs(speedAbs) < 0.05 && DriveStats.of(trail).distance > 1.0) {
                baseline = ArrayList(trail)
                phase = Phase.IDLE
            }
            Phase.READY -> if (place != null && sign != 0 && abs(speedAbs) > 0.05) {
                trail.clear()
                clock = 0.0
                still = 0.0
                phase = Phase.LEAVING
            }
            Phase.LEAVING -> if (place != null) {
                if (crashedInto(place)) return
                still = if (abs(speedAbs) < 0.05) still + dt else 0.0
                val body = spec.body(pose)
                // Out far enough, or out of the space and standing: either way the attempt begins.
                // "Out" is the middle of the car beyond the space and at most one corner left in it.
                // A slanted space pokes one corner into the lane, so a car standing in the lane can
                // still touch its outline and would otherwise never count as having left. A car half
                // out, both rear corners still in, is still on its way out.
                val away = (body.c - place.slot.c).len() > LEAVE_DISTANCE
                val out = body.corners().count { place.slot.contains(it) } <= 1 && !place.slot.contains(body.c)
                if (away || (still > 1.0 && out)) startAttempt()
            }
            Phase.PARKING -> if (place != null) {
                if (armed) {
                    if (crashedInto(place)) return
                } else if (place.hits(spec.body(pose)) == null) {
                    armed = true
                }
                if ((pose.pos - place.target.pos).len() > GIVE_UP) {
                    drop(Drop.FAR)
                    return
                }
                hint = if (tooFast || doorOpen || blind) null else guide?.update(pose, ds, dt, speed, g, wheelDegRight)
                rolled += abs(ds)
                // Park while still on top of what was hit is not an attempt yet, and neither is Park
                // before the car has moved: standing outside the space in P and pressing "다시 연습",
                // or stopping in P just after leaving, used to end at once in a failure of no moves.
                if (armed && rolled > ROLLED && g == Gear.PARK && abs(speedAbs) < 0.05) finish(place, crashed = false)
            }
            else -> Unit
        }
    }

    private fun record(force: Boolean) {
        val prev = trail.lastOrNull()
        val far = prev == null || (prev.pose.pos - pose.pos).len() >= 0.1 ||
            abs(wrap(prev.pose.h - pose.h)) >= Math.toRadians(3.0)
        if (far || force) trail.add(Sample(clock, pose, gear, wheelDeg))
    }

    private fun crashedInto(place: Lot): Boolean {
        val struck = place.hits(spec.body(pose)) ?: return false
        hit = struck
        finish(place, crashed = true)
        return true
    }

    private fun finish(place: Lot, crashed: Boolean) {
        val rel = place.target.relative(pose)
        val angle = Math.toDegrees(abs(rel.h)).let { min(it, 180.0 - it) }
        val inside = place.holds(spec.body(pose), 0.12)
        val stats = DriveStats.of(trail)
        val success = inside && !crashed
        val stars = when {
            !success -> 0
            stats.moves <= 2 && abs(rel.y) < 0.2 && angle < 4.0 -> 3
            stats.moves <= 4 -> 2
            else -> 1
        }
        val gaps = if (place.obstacles.any { it.kind == Kind.CONE }) coneGaps.filter { it < Double.MAX_VALUE } else emptyList()
        val attempt = Attempt(success, crashed, rel.y, rel.x, angle, stats, stars, ArrayList(trail), gaps)
        last = attempt
        advice = Coach.review(attempt, place, planner)
        history.add(attempt)
        hint = null
        phase = if (crashed) Phase.CRASHED else Phase.FINISHED
    }

    /** Starts recording a first attempt from wherever the car is. It ends by itself when Park is selected. */
    fun startRecording() {
        trail.clear()
        clock = 0.0
        baseline = null
        lot = null
        hint = null
        hit = null
        phase = Phase.RECORDING
    }

    /** Makes the car's present position the perfect parked position and lays the situation out around it. */
    fun setTarget(config: LotConfig) {
        val place = preview(config)
        // A first parking recorded here already shows how much of the lane there is to use.
        proven = baseline?.maxOfOrNull { place.reach(spec.body(it.pose)) } ?: 0.0
        adopt(place)
    }

    /**
     * The place [config] describes, laid out round where the car stands now, without making it the
     * target yet: what [setTarget] would adopt. It is what is shown while the place is being described.
     */
    fun preview(config: LotConfig): Lot {
        val place = laidOut(config, pose)
        val before = baseline ?: return place
        if (config.id != Scenarios.REAL || config.type == SlotType.PARALLEL) return place
        // The far side is drawn where the recorded drive turned back: no further than the car went.
        val used = before.maxOfOrNull { place.reach(spec.body(it.pose)) } ?: 0.0
        return laidOut(config.copy(aisle = (used + LANE_SPARE).coerceIn(spec.length + 0.1, 9.0)), pose)
    }

    /**
     * True when the first parking recorded here could not have happened in the place [config]
     * describes: it would have gone through a wall. The description is wrong, most often about
     * which way the car went in.
     */
    fun misfit(config: LotConfig): Boolean {
        val before = baseline ?: return false
        return preview(config).through(before.map { it.pose }, spec) != null
    }

    private fun laidOut(config: LotConfig, parked: Pose): Lot {
        val place = LotBuilder.build(config, spec, parked)
        return if (cones) place.withCones() else place
    }

    private fun adopt(place: Lot) {
        lot = place
        val p = Planner(spec, place, keepOut = { limit })
        planner = p
        guide = Guide(spec, p, place)
        hint = null
        hit = null
        phase = Phase.READY
    }

    /** Begins a guided attempt from wherever the car is now. */
    fun startAttempt() {
        val place = lot ?: return
        val p = planner ?: return
        guide = Guide(spec, p, place)
        trail.clear()
        clock = 0.0
        hint = null
        hit = null
        coneGaps.fill(Double.MAX_VALUE)
        // Straight after a crash the car is usually still over what it hit: that is not a new touch.
        armed = place.hits(spec.body(pose)) == null
        rolled = 0.0
        phase = Phase.PARKING
    }

    /** After an attempt: back to waiting in the space, or straight into another attempt after a crash. */
    fun again() {
        val place = lot ?: return
        hit = null
        hint = null
        if (place.holds(spec.body(pose), 0.3)) phase = Phase.READY else startAttempt()
    }

    /**
     * Real lots only: the driver confirms the car is properly parked, so the model's position is put
     * back on the target. This throws away whatever drift built up during the attempt.
     */
    fun reanchor() {
        val place = lot ?: return
        pose = place.target
        hit = null
        phase = Phase.READY
    }

    /**
     * Keeps the same parking place but changes everything around it: new neighbours, walls or lane.
     * The first attempt stays on record, so later ones can still be compared with it.
     */
    fun retarget(config: LotConfig) {
        val place = lot ?: return
        val rebuilt = laidOut(config, place.target)
        if (place.holds(spec.body(pose), 0.3)) pose = rebuilt.target
        // The same ground as before, so what is known to be clear stays known.
        adopt(rebuilt)
    }

    fun reset() {
        history.clear()
        baseline = null
        proven = 0.0
        lot = null
        planner = null
        guide = null
        hint = null
        hit = null
        trail.clear()
        armed = true
        phase = Phase.IDLE
    }

    /** Gives the practice up: the place and what was learned there go, and [dropped] says why. */
    private fun drop(why: Drop) {
        reset()
        dropped = why
    }

    companion object {
        /** Above this the trainer goes quiet: 15 km/h. */
        const val MAX_SPEED = 15.0 / 3.6
        const val LEAVE_DISTANCE = 6.0

        /** Metres an attempt has to have been driven before selecting Park ends it. */
        const val ROLLED = 0.1

        /** Metres from the space beyond which no plan ever goes ([Planner.plan]): the practice is over. */
        const val GIVE_UP = 30.0
        /** Drawn beyond where a recorded first parking turned back, so the far side is not under the car. */
        const val LANE_SPARE = 0.4
    }
}
