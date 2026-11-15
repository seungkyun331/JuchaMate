package com.parkcoach.core

import kotlin.math.abs

enum class Tip {
    CLEAN,
    TOUCHED,

    /** Began reversing too far past the space, or not far enough. */
    STARTED_LATE,
    STARTED_EARLY,
    MANY_MOVES,
    LEFT_OF_CENTRE,
    RIGHT_OF_CENTRE,
    CROOKED,

    /** Stopped with the nose still out, or went too far back. */
    TOO_SHALLOW,
    TOO_DEEP,
}

data class Advice(val tip: Tip, val amount: Double = 0.0)

/**
 * Looks at a finished attempt the way an instructor in the passenger seat would: not just whether
 * the car ended up in the space, but which one or two habits made it harder than it had to be.
 */
object Coach {
    fun review(a: Attempt, lot: Lot, planner: Planner?): List<Advice> {
        val out = ArrayList<Advice>()
        if (a.crashed) out.add(Advice(Tip.TOUCHED))

        // Where the driver began to reverse, compared with the best place to begin.
        val firstBack = a.samples.firstOrNull { it.gear == Gear.REVERSE }
        val reversedIn = lot.config.type == SlotType.PERPENDICULAR || lot.config.type == SlotType.PARALLEL
        if (firstBack != null && planner != null && reversedIn) {
            val rel = lot.target.relative(firstBack.pose)
            val best = planner.setup(toLeft = rel.h >= 0)
            if (best != null && planner.direct(best) != null) {
                val ideal = lot.target.relative(best)
                val parallel = lot.config.type == SlotType.PARALLEL
                val past = if (parallel) rel.x - ideal.x else abs(rel.y) - abs(ideal.y)
                if (past > 0.5) out.add(Advice(Tip.STARTED_LATE, past))
                if (past < -0.5) out.add(Advice(Tip.STARTED_EARLY, -past))
            }
        }
        if (a.stats.moves > 2) out.add(Advice(Tip.MANY_MOVES, a.stats.moves.toDouble()))
        if (!a.crashed) {
            if (a.sideways > 0.2) out.add(Advice(Tip.LEFT_OF_CENTRE, a.sideways))
            if (a.sideways < -0.2) out.add(Advice(Tip.RIGHT_OF_CENTRE, -a.sideways))
            if (a.angleDeg > 4.0) out.add(Advice(Tip.CROOKED, a.angleDeg))
            if (a.lengthways > 0.3) out.add(Advice(Tip.TOO_SHALLOW, a.lengthways))
            if (a.lengthways < -0.25) out.add(Advice(Tip.TOO_DEEP, -a.lengthways))
        }
        if (out.isEmpty()) out.add(Advice(Tip.CLEAN))
        return out.take(3)
    }
}
