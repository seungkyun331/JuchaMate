package com.parkcoach.core

import kotlin.math.PI
import kotlin.math.abs

/**
 * What a recorded parking says about the place it ended in: how the car went in, and so where the
 * lane is.
 *
 * A place is laid out round the parked car ([LotBuilder]), and which side the lane goes on follows
 * from how the car is said to have gone in. Reversed in, the lane is in front of it and the wall
 * behind; driven in nose-first, the other way round. Nobody was asked, and "reversed in" was assumed:
 * a driver who had gone in nose-first was then shown a lane on the far side of the wall in front of
 * the car, and the drive just recorded coming in through the wall behind it.
 *
 * The drive itself says which it was. Where the car was before it began to park is the lane: ahead of
 * where it ended or behind, across it or alongside. That is all this reads.
 */
data class Entry(
    val type: SlotType,
    /** Slanted bays: degrees between the bay and the lane, as one of the two that are laid out. */
    val angle: Double = 60.0,
    /** The place lies the other way round from how one is normally laid out (see [LotConfig.mirror]). */
    val mirror: Boolean = false,
) {
    /** [config] with the car going in the way this drive did. */
    fun on(config: LotConfig): LotConfig = config.copy(type = type, angle = angle, mirror = mirror)

    companion object {
        /** A drive shorter than this, in metres, shows too little to say anything. */
        const val SHORT = 3.0

        /**
         * How far back along the drive, in metres from its end, the car is taken to have been in the
         * lane still. Parking takes about nine metres: a quarter turn into a bay and the straight
         * after it, or the S along a kerb. Further back than this the driver may have been anywhere.
         */
        const val LANE_FROM = 9.0
        const val LANE_TO = 14.0

        /** Degrees between lane and car above which the space is square to the lane, and below which it lies along it. */
        const val SQUARE = 67.5
        const val ALONG = 35.0

        /** Metres to one side that a lane running along the car has to be for the space to be one along a kerb. */
        const val BESIDE = 1.0

        /** Reads a recorded drive, oldest sample first, that ends with the car parked. Null when it shows too little. */
        fun read(samples: List<Sample>): Entry? {
            if (samples.size < 2) return null
            val end = samples.last().pose
            // Metres still to go at each sample, counted back from where the car stopped.
            val togo = DoubleArray(samples.size)
            for (i in samples.size - 2 downTo 0) {
                togo[i] = togo[i + 1] + (samples[i + 1].pose.pos - samples[i].pose.pos).len()
            }
            val driven = togo[0]
            if (driven < SHORT) return null

            // The car while it was still in the lane. A short recording has only its first metre to go by.
            var lane = samples.indices.filter { togo[it] >= LANE_FROM && togo[it] <= LANE_TO }
            if (lane.isEmpty()) lane = samples.indices.filter { togo[it] >= driven - 1.0 }
            if (lane.isEmpty()) return null

            // Where that was, seen from the parked car: x ahead of it, y to its left.
            var ahead = 0.0
            var aside = 0.0
            for (i in lane) {
                val at = end.toLocal(samples[i].pose.pos)
                ahead += at.x
                aside += at.y
            }
            ahead /= lane.size
            aside /= lane.size

            // How far the car has turned since, and how far off square or parallel the lane is.
            val turns = lane.map { wrap(samples[it].pose.h - end.h) }.sorted()
            val turn = turns[turns.size / 2]
            val offs = turns.map { Math.toDegrees(minOf(abs(it), PI - abs(it))) }.sorted()
            val off = offs[offs.size / 2]

            return when {
                // The lane runs across the space: an ordinary bay, entered from whichever end the lane is at.
                off >= SQUARE -> Entry(if (ahead >= 0) SlotType.PERPENDICULAR else SlotType.NOSE_IN)
                // The lane runs at a slant behind the car: a slanted bay, driven into. The car turned
                // left into one laid out the usual way, and right into its mirror image.
                off >= ALONG && ahead < 0 -> Entry(SlotType.ANGLED, if (off < 52.5) 45.0 else 60.0, mirror = turn > 0)
                // Backed into a slanted bay: not a kind that is laid out, so the nearest that puts the lane in front.
                off >= ALONG -> Entry(SlotType.PERPENDICULAR)
                // The lane runs alongside: a space along a kerb, with the road on the side the car came from.
                abs(aside) >= BESIDE -> Entry(SlotType.PARALLEL, mirror = aside < 0)
                // Straight in from the lane, with no turn at all.
                else -> Entry(if (ahead >= 0) SlotType.PERPENDICULAR else SlotType.NOSE_IN)
            }
        }
    }
}
