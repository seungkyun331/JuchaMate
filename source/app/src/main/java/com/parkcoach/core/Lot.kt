package com.parkcoach.core

import kotlin.math.PI

/** Reverse into a bay, drive nose-first into a bay, drive into a slanted bay, or park along the kerb. */
enum class SlotType { PERPENDICULAR, NOSE_IN, ANGLED, PARALLEL }

/**
 * What stands next to the target space. A WALL also closes that end of the lane; a PARTITION is a
 * wall only as long as the space, as in a garage; a PILLAR is a column at the mouth, as in a basement.
 */
enum class Side { CAR, WALL, EMPTY, PARTITION, PILLAR }

/** How big the car parked next to the space is. Metres, length by width. */
enum class CarSize(val length: Double, val width: Double) { SMALL(3.6, 1.6), NORMAL(4.5, 1.85), LARGE(5.15, 1.98) }

/**
 * How the car next to the space was left: square in its own space, with its nose out in the lane,
 * or hard against the line on my side. The last two are what make a real space harder than a drawn one.
 */
enum class Stance { STRAIGHT, FORWARD, CLOSE }

/** What is across the driving lane. */
enum class Opposite { OPEN, WALL, CARS }

/** The three places around the space where something can be put: [LotConfig.left], [LotConfig.right] and across the lane. */
enum class Spot { LEFT, RIGHT, OPPOSITE }

/**
 * A parking situation described by a handful of choices. The presets, the custom editor and the
 * real-lot questionnaire all produce one of these, and [LotBuilder] turns it into geometry.
 * For a parallel space, [left] is what is behind the space and [right] what is ahead of it.
 */
data class LotConfig(
    val id: String,
    val name: String,
    val type: SlotType = SlotType.PERPENDICULAR,
    val left: Side = Side.CAR,
    val right: Side = Side.CAR,
    val opposite: Opposite = Opposite.OPEN,
    /** Width of the driving lane in front of the space. */
    val aisle: Double = 6.5,
    /** Bays: width of one space. Parallel: spare length beyond the car. */
    val room: Double = 2.5,
    /** Slanted bays only: degrees between the bay and the lane. */
    val angle: Double = 60.0,
    /** When [left] is a car: how big it is and how it was left. */
    val leftSize: CarSize = CarSize.NORMAL,
    val leftStance: Stance = Stance.STRAIGHT,
    /** The same for [right]. */
    val rightSize: CarSize = CarSize.NORMAL,
    val rightStance: Stance = Stance.STRAIGHT,
    /**
     * The place lies the other way round. Along a kerb, the kerb is on the car's left and the road
     * on its right (a one-way street, or driving on the left). In a slanted row, the bays lean the
     * other way, so the car turns right into one instead of left. Square bays are the same either
     * way round, and for them this says nothing.
     */
    val mirror: Boolean = false,
) {
    fun encode(): String = listOf(
        id, name, type.name, left.name, right.name, opposite.name, aisle, room, angle,
        leftSize.name, leftStance.name, rightSize.name, rightStance.name, if (mirror) 1 else 0,
    ).joinToString("|")

    /** True when the layout really is turned over: only the kinds of space that have a handedness. */
    val mirrored: Boolean get() = mirror && (type == SlotType.PARALLEL || type == SlotType.ANGLED)

    fun size(spot: Spot): CarSize = if (spot == Spot.LEFT) leftSize else rightSize

    fun stance(spot: Spot): Stance = if (spot == Spot.LEFT) leftStance else rightStance

    fun side(spot: Spot): Side = if (spot == Spot.LEFT) left else right

    companion object {
        /** Lots saved before cars had a size read back as ordinary cars parked straight. */
        fun decode(line: String): LotConfig? = try {
            val p = line.split("|")
            LotConfig(
                p[0], p[1], SlotType.valueOf(p[2]), Side.valueOf(p[3]), Side.valueOf(p[4]),
                Opposite.valueOf(p[5]), p[6].toDouble(), p[7].toDouble(), p.getOrNull(8)?.toDouble() ?: 60.0,
                p.getOrNull(9)?.let { CarSize.valueOf(it) } ?: CarSize.NORMAL,
                p.getOrNull(10)?.let { Stance.valueOf(it) } ?: Stance.STRAIGHT,
                p.getOrNull(11)?.let { CarSize.valueOf(it) } ?: CarSize.NORMAL,
                p.getOrNull(12)?.let { Stance.valueOf(it) } ?: Stance.STRAIGHT,
                p.getOrNull(13) == "1",
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Scenarios {
    const val CUSTOM = "custom"
    const val REAL = "real"

    val all: List<LotConfig> = listOf(
        LotConfig("basic", "기본 후진 주차", aisle = 7.0),
        LotConfig("facing_cars", "맞은편에 차", opposite = Opposite.CARS, aisle = 6.0),
        LotConfig("facing_wall", "맞은편에 벽", opposite = Opposite.WALL, aisle = 6.0),
        LotConfig("end_wall", "벽 옆 끝자리", left = Side.WALL, opposite = Opposite.CARS, aisle = 6.0),
        LotConfig("pillar", "지하 주차장 기둥 옆", left = Side.PILLAR, opposite = Opposite.CARS, aisle = 6.0),
        LotConfig("garage", "차고 (양옆 벽)", left = Side.PARTITION, right = Side.PARTITION, opposite = Opposite.WALL, aisle = 6.5, room = 3.0),
        LotConfig("narrow_aisle", "좁은 통로", opposite = Opposite.WALL, aisle = 5.0),
        LotConfig("tight_slot", "좁은 칸", opposite = Opposite.CARS, aisle = 6.0, room = 2.3),
        LotConfig("sticking_out", "앞으로 나온 옆 차", opposite = Opposite.CARS, aisle = 6.0, leftStance = Stance.FORWARD, rightSize = CarSize.SMALL),
        LotConfig("big_neighbour", "큰 차 옆 자리", opposite = Opposite.CARS, aisle = 6.0, leftSize = CarSize.LARGE, rightSize = CarSize.LARGE, rightStance = Stance.CLOSE),
        LotConfig("nose_in", "전면 주차", type = SlotType.NOSE_IN, opposite = Opposite.OPEN, aisle = 8.0),
        LotConfig("angled60", "사선 주차 60°", type = SlotType.ANGLED, opposite = Opposite.WALL, aisle = 5.5, angle = 60.0),
        LotConfig("angled45", "사선 주차 45°", type = SlotType.ANGLED, opposite = Opposite.WALL, aisle = 4.5, angle = 45.0),
        LotConfig("parallel", "평행 주차", type = SlotType.PARALLEL, opposite = Opposite.OPEN, aisle = 5.0, room = 2.8),
        LotConfig("parallel_tight", "빠듯한 평행 주차", type = SlotType.PARALLEL, opposite = Opposite.CARS, aisle = 4.0, room = 2.3),
    )

    fun byId(id: String?): LotConfig? = all.firstOrNull { it.id == id }
}

/** A CONE is a real one the driver has stood at the mouth of the space; the rest exist only on screen. */
enum class Kind { CAR, WALL, KERB, CONE }

data class Obstacle(val box: Box, val kind: Kind)

/** A parking place laid out in the world: the space, where the car should end up, and what must not be touched. */
data class Lot(
    val config: LotConfig,
    val slot: Box,
    /** Rear-axle pose of a perfectly parked car. */
    val target: Pose,
    val obstacles: List<Obstacle>,
    /** Painted lines, as pairs of end points. */
    val lines: List<Pair<Vec, Vec>>,
    /** The direction on the ground that points from the lane into the row of spaces. */
    val inward: Double,
    /** Where on the ground each [Spot] is, for marking it in a picture. */
    val spots: Map<Spot, Vec> = emptyMap(),
) {
    fun hits(body: Box): Obstacle? = obstacles.firstOrNull { it.box.intersects(body) }

    /** Metres from the car to the nearest thing it must not touch. */
    fun clearance(body: Box): Double = obstacles.minOfOrNull { it.box.distance(body) } ?: Double.MAX_VALUE

    /** Metres from one point, such as a corner of the car, to the nearest obstacle. */
    fun gap(p: Vec): Double = obstacles.minOfOrNull { it.box.gap(p) } ?: Double.MAX_VALUE

    fun holds(body: Box, slack: Double = 0.05): Boolean = body.corners().all { slot.contains(it, slack) }

    /** The direction on the ground from the row of spaces out into the lane. */
    val out: Vec get() = Vec(-kotlin.math.cos(inward), -kotlin.math.sin(inward))

    /** Metres from the middle of the space to where the lane begins. */
    val mouth: Double get() = if (config.type == SlotType.PARALLEL) slot.width / 2 else slot.length / 2

    /** How far beyond the mouth of the space, out into the lane, a car's body reaches. */
    fun reach(body: Box): Double = body.corners().maxOf { (it - slot.c).dot(out) } - mouth

    /**
     * A strip lying across the lane [depth] metres out from the mouth of the space: the far edge of
     * where the car is known to have been. Nothing is drawn as standing there; it only marks how far
     * out guidance may take the car.
     */
    fun strip(depth: Double): Box = Box(slot.c + out * (mouth + depth + STRIP / 2), wrap(inward + PI / 2), 80.0, STRIP)

    /** Where two real cones go: just outside the two front corners of the space. */
    fun conePlaces(): List<Vec> {
        val along = Vec(-out.y, out.x)
        val half = (if (config.type == SlotType.PARALLEL) slot.length else slot.width) / 2 + CONE_OUTSIDE
        val front = slot.c + out * mouth
        return listOf(front + along * half, front - along * half)
    }

    /**
     * The first wall or kerb that a car driving along [path] would have gone through. A drive that
     * really happened went through none, so finding one says the place is not as it was described:
     * the lane on the wrong side of the car, or a wall where the car came from.
     */
    fun through(path: List<Pose>, spec: VehicleSpec): Obstacle? {
        for (p in path) {
            // Well inside the car's outline: brushing past a wall that is drawn a hand's width off is not going through it.
            val body = spec.body(p).grow(-THROUGH)
            obstacles.firstOrNull { (it.kind == Kind.WALL || it.kind == Kind.KERB) && it.box.intersects(body) }?.let { return it }
        }
        return null
    }

    /** The same place with a cone standing at each front corner of the space. */
    fun withCones(): Lot = copy(obstacles = obstacles + conePlaces().map { Obstacle(Box(it, slot.h, CONE, CONE), Kind.CONE) })

    companion object {
        private const val STRIP = 0.2

        /** How far inside the car's outline a wall has to come before a recorded drive counts as having gone through it. */
        const val THROUGH = 0.25

        /** A cone's footprint, and how far outside the line of the space it stands. */
        const val CONE = 0.22
        const val CONE_OUTSIDE = 0.15
    }
}

object LotBuilder {
    private const val CAR_LENGTH = 4.5
    private const val CAR_WIDTH = 1.85
    private const val WALL = 0.3
    const val SLOT_LENGTH = 5.0

    /** Along a kerb: how far a car parked "close" has come into the gap. */
    private const val CROWD = 0.35

    /** The narrowest nose-first bay, for an ordinary car, that one swing still enters beside such a neighbour. */
    private fun noseRoom(size: CarSize, stance: Stance): Double = when (size) {
        CarSize.SMALL -> 2.5
        CarSize.NORMAL -> when (stance) { Stance.STRAIGHT -> 2.5; Stance.FORWARD -> 2.7; Stance.CLOSE -> 2.9 }
        CarSize.LARGE -> when (stance) { Stance.STRAIGHT -> 2.7; Stance.FORWARD -> 2.8; Stance.CLOSE -> 3.1 }
    }
    const val PARALLEL_WIDTH = 2.3
    const val KERB_OVERHANG = 0.3

    /**
     * Lays the situation out around a car standing at [parked], which becomes the perfect parked position.
     * Everything is placed in the space's own frame (x toward where the nose points, y to the car's left)
     * and then moved into the world.
     */
    fun build(asked: LotConfig, spec: VehicleSpec, parked: Pose): Lot {
        val bay = asked.type != SlotType.PARALLEL
        val slanted = asked.type == SlotType.ANGLED
        // A lane narrower than the car is long cannot be used at all, so it is never drawn that way.
        val config = if (bay && !slanted && asked.opposite != Opposite.OPEN) {
            asked.copy(aisle = maxOf(asked.aisle, spec.length + 0.1))
        } else {
            asked
        }
        val center = spec.body(parked).c
        // The layout frame always has x pointing out of the space toward the lane. A car that
        // drove in nose-first faces the other way, so for those the frame is turned round.
        val noseFirst = config.type == SlotType.NOSE_IN || slanted
        val f = Pose(center.x, center.y, if (noseFirst) wrap(parked.h + PI) else parked.h)
        // A place that lies the other way round is the same layout turned over: everything below is
        // worked out the usual way and its left and right are swapped as it is put into the world.
        val my = if (config.mirrored) -1.0 else 1.0
        // The layout's left is the driver's left, unless exactly one of the two turns it round.
        val swapped = noseFirst != config.mirrored
        val onLeft = if (swapped) config.right else config.left
        val onRight = if (swapped) config.left else config.right
        // The car next door, on the layout's left (+1) or right (-1).
        fun sizeOn(sign: Int) = if ((sign > 0) != swapped) config.leftSize else config.rightSize
        fun stanceOn(sign: Int) = if ((sign > 0) != swapped) config.leftStance else config.rightStance
        val obstacles = ArrayList<Obstacle>()
        val lines = ArrayList<Pair<Vec, Vec>>()
        val spots = HashMap<Spot, Vec>()

        /** A point of the layout, in the world. */
        fun world(v: Vec) = f.toWorld(Vec(v.x, my * v.y))

        fun box(x: Double, y: Double, length: Double, width: Double, turn: Double = 0.0) =
            Box(world(Vec(x, y)), wrap(f.h + my * turn), length, width)

        fun line(x1: Double, y1: Double, x2: Double, y2: Double) {
            lines.add(world(Vec(x1, y1)) to world(Vec(x2, y2)))
        }

        val slot: Box
        var inward = wrap(f.h + PI)
        if (bay) {
            // Something solid on the line itself eats into the space, so such a bay is never drawn
            // narrower than a car plus a door's width.
            val solid = listOf(onLeft, onRight).any { it == Side.PARTITION || it == Side.PILLAR }
            // Driving in nose-first leaves no room to correct, so beside a big or badly parked car
            // such a bay is never drawn narrower than one swing can enter.
            val swing = if (config.type == SlotType.NOSE_IN) {
                listOf(1, -1).maxOf { sign ->
                    if ((if (sign > 0) onLeft else onRight) != Side.CAR) 0.0 else noseRoom(sizeOn(sign), stanceOn(sign)) + spec.width - 1.89
                }
            } else {
                0.0
            }
            val w = maxOf(config.room.coerceIn(spec.width + 0.3, 3.5), if (solid) spec.width + 0.8 else 0.0, swing)
            val l = maxOf(SLOT_LENGTH, spec.length + 0.4)
            slot = box(0.0, 0.0, l, w)
            val mouth = l / 2
            // In a slanted row the lane runs at an angle to the bays: [skew] is how far off square.
            val skew = if (slanted) Math.toRadians(90.0 - config.angle.coerceIn(40.0, 80.0)) else 0.0
            val cs = kotlin.math.cos(skew)
            val sn = kotlin.math.sin(skew)
            val pitch = w / cs
            val reach = mouth * cs + w / 2 * sn
            val far = reach + config.aisle
            val row = skew + PI / 2
            inward = wrap(f.h + my * skew + PI)

            /** A point given as (toward the lane, along the row), in the layout frame. */
            fun at(out: Double, along: Double) = Vec(out * cs - along * sn, out * sn + along * cs)

            spots[if (swapped) Spot.RIGHT else Spot.LEFT] = world(at(0.0, pitch))
            spots[if (swapped) Spot.LEFT else Spot.RIGHT] = world(at(0.0, -pitch))
            spots[Spot.OPPOSITE] = world(at(far + 1.6, 0.0))

            // Behind the row: a wall, so going in too far counts.
            val back = at(-reach - WALL / 2 - 0.05, 0.0)
            obstacles.add(Obstacle(box(back.x, back.y, pitch * 9, WALL, row), Kind.WALL))

            for (i in -4..4) {
                val edge = at(0.0, (i + 0.5) * pitch)
                // No painted spaces on the far side of a wall.
                val walledOff = (i >= 1 && onLeft == Side.WALL) || (i <= -2 && onRight == Side.WALL)
                if (!walledOff) line(edge.x - mouth, edge.y, edge.x + mouth, edge.y)
                if (i == 0) continue
                val side = if (i > 0) onLeft else onRight
                if (side == Side.WALL) continue
                if (kotlin.math.abs(i) == 1 && side == Side.EMPTY) continue
                if (kotlin.math.abs(i) == 1 && side == Side.CAR) {
                    // The one next to the space is the one that matters: it can be any size, and
                    // it is not always left square in the middle of its lines.
                    val sign = if (i > 0) 1 else -1
                    val size = sizeOn(sign)
                    val stance = stanceOn(sign)
                    // A car longer than the space keeps its tail off the wall, so its nose is out.
                    val settled = maxOf(0.0, (size.length - l) / 2 + 0.1)
                    val out = if (stance == Stance.FORWARD) {
                        minOf(maxOf(settled + 0.7, mouth + 0.3 - size.length / 2), mouth + 0.7 - size.length / 2)
                    } else {
                        settled
                    }
                    val crowd = if (stance == Stance.CLOSE) ((w - size.width) / 2 - 0.03).coerceIn(0.0, 0.3) else 0.0
                    val c = at(out, i * pitch - sign * crowd)
                    obstacles.add(Obstacle(box(c.x, c.y, size.length, size.width), Kind.CAR))
                    continue
                }
                val c = at(0.0, i * pitch)
                obstacles.add(Obstacle(box(c.x, c.y, CAR_LENGTH, CAR_WIDTH), Kind.CAR))
            }
            for (sign in listOf(1, -1)) {
                when (if (sign > 0) onLeft else onRight) {
                    Side.WALL -> if (!slanted) {
                        // A wall beside the space also closes that end of the lane.
                        val depth = l + config.aisle
                        obstacles.add(Obstacle(box(-mouth + depth / 2, sign * (w / 2 + WALL / 2), depth, WALL), Kind.WALL))
                    }
                    Side.PARTITION -> if (!slanted) obstacles.add(Obstacle(box(0.0, sign * w / 2, l, 0.2), Kind.WALL))
                    Side.PILLAR -> if (!slanted) obstacles.add(Obstacle(box(mouth - 0.6, sign * w / 2, 0.6, 0.3), Kind.WALL))
                    else -> Unit
                }
            }
            when (if (slanted && config.opposite == Opposite.CARS) Opposite.WALL else config.opposite) {
                Opposite.WALL -> {
                    val c = at(far + WALL / 2, 0.0)
                    obstacles.add(Obstacle(box(c.x, c.y, pitch * 9, WALL, row), Kind.WALL))
                }
                Opposite.CARS -> for (i in -4..4) {
                    line(far, (i + 0.5) * w, far + l, (i + 0.5) * w)
                    val blocked = (i > 0 && onLeft == Side.WALL) || (i < 0 && onRight == Side.WALL)
                    if (!blocked) obstacles.add(Obstacle(box(far + l / 2, i * w, CAR_LENGTH, CAR_WIDTH, PI), Kind.CAR))
                }
                Opposite.OPEN -> Unit
            }
        } else {
            // A car that has taken some of the gap, or stands out from the kerb, leaves less to
            // swing into: the gap is never drawn shorter than can still be entered.
            val stances = listOf(Spot.LEFT, Spot.RIGHT).filter { config.side(it) == Side.CAR }.map { config.stance(it) }
            val taken = stances.count { it == Stance.CLOSE } * CROWD
            var spare = config.room.coerceIn(1.2, 4.0)
            if (stances.any { it == Stance.FORWARD }) spare = maxOf(spare, 2.8)
            if (taken > 0) spare = maxOf(spare, 2.5 + taken)
            val l = spec.length + spare
            val w = maxOf(PARALLEL_WIDTH, spec.width + 0.4)
            slot = box(0.0, 0.0, l, w)
            val kerb = -w / 2
            val far = w / 2 + config.aisle

            spots[Spot.RIGHT] = world(Vec(l / 2 + CAR_LENGTH / 2 + 0.05, 0.0))
            spots[Spot.LEFT] = world(Vec(-l / 2 - CAR_LENGTH / 2 - 0.05, 0.0))
            spots[Spot.OPPOSITE] = world(Vec(0.0, far + CAR_WIDTH / 2 + 0.2))

            // A kerb is low: the tail may swing over its edge, so it only counts a little way in.
            obstacles.add(Obstacle(box(0.0, kerb - WALL / 2 - KERB_OVERHANG, l * 5, WALL), Kind.KERB))
            line(-l * 2.5, w / 2, l * 2.5, w / 2)
            for (i in -2..2) line((i + 0.5) * l, kerb, (i + 0.5) * l, w / 2)

            for (sign in listOf(1, -1)) {
                val side = if (sign > 0) config.right else config.left
                val edge = sign * l / 2
                when (side) {
                    Side.CAR -> {
                        // Along a kerb, "close" is a car that has taken some of the gap, and
                        // "forward" one that stands out from the kerb into the road.
                        val size = if (sign > 0) config.rightSize else config.leftSize
                        val stance = if (sign > 0) config.rightStance else config.leftStance
                        val gap = if (stance == Stance.CLOSE) 0.05 - CROWD else 0.05
                        val off = (size.width - CAR_WIDTH) / 2 + (if (stance == Stance.FORWARD) 0.35 else 0.0)
                        obstacles.add(Obstacle(box(edge + sign * (size.length / 2 + gap), off, size.length, size.width), Kind.CAR))
                        obstacles.add(Obstacle(box(edge + sign * (l + CAR_LENGTH / 2), 0.0, CAR_LENGTH, CAR_WIDTH), Kind.CAR))
                    }
                    Side.WALL -> obstacles.add(Obstacle(box(edge + sign * WALL / 2, w / 4, WALL, w * 1.5), Kind.WALL))
                    else -> Unit
                }
            }
            when (config.opposite) {
                Opposite.WALL -> obstacles.add(Obstacle(box(0.0, far + WALL / 2, l * 5, WALL), Kind.WALL))
                Opposite.CARS -> for (i in -2..2) {
                    obstacles.add(Obstacle(box(i * (CAR_LENGTH + 1.2), far + CAR_WIDTH / 2 + 0.2, CAR_LENGTH, CAR_WIDTH, PI), Kind.CAR))
                }
                Opposite.OPEN -> Unit
            }
        }
        if (!bay) inward = wrap(f.h - my * PI / 2)
        return Lot(config, slot, parked, obstacles, lines, inward, spots)
    }
}
