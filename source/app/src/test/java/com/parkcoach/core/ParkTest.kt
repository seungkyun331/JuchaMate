package com.parkcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class ParkTest {
    private val dt = 1.0 / 30.0
    private val origin = Pose(3.0, -2.0, 0.7)

    /** Lets the stand-in car follow the guide until the attempt ends or time runs out. */
    private fun drive(engine: Engine, car: SimCar, seconds: Double = 240.0): Attempt? {
        var t = 0.0
        while (t < seconds && engine.phase == Phase.PARKING) {
            car.follow(engine.hint)
            car.step(dt)
            engine.step(dt, car.speed, car.wheelDeg, car.gear)
            t += dt
        }
        return if (engine.phase == Phase.FINISHED || engine.phase == Phase.CRASHED) engine.last else null
    }

    private fun attemptFrom(spec: VehicleSpec, config: LotConfig, start: (Lot, Planner) -> Pose?): Attempt? {
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(config)
        val lot = engine.lot!!
        val from = start(lot, Planner(spec, lot)) ?: return null
        engine.teleport(from)
        engine.startAttempt()
        val car = SimCar(spec)
        car.gear = Gear.DRIVE
        return drive(engine, car)
    }

    @Test
    fun aFullCircleComesBackToTheStart() {
        val spec = Vehicles.default
        var p = Pose(1.0, 2.0, 0.3)
        val road = spec.maxRoad * 0.8
        val radius = spec.wheelbase / kotlin.math.tan(road)
        val steps = 1000
        for (i in 0 until steps) p = Motion.advance(p, 2 * PI * radius / steps, road, spec.wheelbase)
        assertEquals(1.0, p.x, 1e-6)
        assertEquals(2.0, p.y, 1e-6)
        assertEquals(0.3, p.h, 1e-6)
        val back = Motion.advance(Motion.advance(p, 3.0, road, spec.wheelbase), -3.0, road, spec.wheelbase)
        assertEquals(p.x, back.x, 1e-9)
        assertEquals(p.h, back.h, 1e-9)
    }

    @Test
    fun rectanglesKnowWhenTheyTouch() {
        val a = Box(Vec(0.0, 0.0), 0.0, 4.0, 2.0)
        assertTrue(a.intersects(Box(Vec(3.9, 0.0), 0.0, 4.0, 2.0)))
        assertFalse(a.intersects(Box(Vec(4.1, 0.0), 0.0, 4.0, 2.0)))
        assertTrue(a.intersects(Box(Vec(3.0, 2.0), PI / 4, 4.0, 2.0)))
        assertFalse(a.intersects(Box(Vec(3.6, 2.9), PI / 4, 2.0, 1.0)))
        assertTrue(a.contains(Vec(1.9, 0.9)))
        assertFalse(a.contains(Vec(2.1, 0.0)))
    }

    @Test
    fun everyPresetCanBeParkedInByEveryCar() {
        for (spec in Vehicles.presets) {
            for (config in Scenarios.all) {
                val attempt = attemptFrom(spec, config) { lot, planner ->
                    // The green box when one swing is enough; otherwise the middle of the lane, just past the space.
                    planner.setup(toLeft = config.left != Side.WALL)
                        ?: lot.target.compose(Pose(spec.centerAhead + lot.slot.length / 2 + config.aisle / 2, -3.0, -PI / 2))
                }
                assertNotNull("${spec.id} never finished ${config.id}", attempt)
                assertTrue("${spec.id} failed ${config.id}: $attempt", attempt!!.success)
                assertTrue("${spec.id} sat crooked in ${config.id}", abs(attempt.sideways) < 0.3 && attempt.angleDeg < 6.0)
            }
        }
    }

    @Test
    fun neighboursOfAnySizeHoweverTheyStandLeaveAWayIn() {
        val spec = Vehicles.default
        val bases = listOf(
            LotConfig("t1", "bay", opposite = Opposite.CARS, aisle = 6.0),
            LotConfig("t2", "nose", type = SlotType.NOSE_IN, aisle = 8.0),
            LotConfig("t3", "kerb", type = SlotType.PARALLEL, aisle = 5.0, room = 2.8),
        )
        for (base in bases) {
            for (size in CarSize.values()) {
                for (stance in Stance.values()) {
                    val config = base.copy(leftSize = size, leftStance = stance, rightSize = size, rightStance = stance)
                    val attempt = attemptFrom(spec, config) { lot, planner ->
                        planner.setup(toLeft = true)
                            ?: lot.target.compose(Pose(spec.centerAhead + lot.slot.length / 2 + config.aisle / 2, -3.0, -PI / 2))
                    }
                    assertNotNull("never finished ${base.name} beside $size $stance", attempt)
                    assertTrue("failed ${base.name} beside $size $stance: $attempt", attempt!!.success)
                }
            }
        }
    }

    @Test
    fun aCarLeftForwardOrCloseTakesRoomAway() {
        val spec = Vehicles.default
        val parked = Pose(0.0, 0.0, PI / 2)
        val body = spec.body(parked)
        val plain = LotBuilder.build(LotConfig("a", "a"), spec, parked)
        val close = LotBuilder.build(LotConfig("b", "b", leftStance = Stance.CLOSE, leftSize = CarSize.LARGE), spec, parked)
        fun beside(lot: Lot) = lot.obstacles.filter { it.kind == Kind.CAR }.minOf { it.box.distance(body) }
        assertTrue(beside(close) < beside(plain) - 0.2)
        // Sticking out: the corner of the lane in front of the next space is no longer free.
        val forward = LotBuilder.build(LotConfig("c", "c", leftStance = Stance.FORWARD), spec, parked)
        val mouth = plain.spots[Spot.LEFT]!! + Vec(0.0, plain.slot.length / 2 + 0.3)
        assertTrue(plain.gap(mouth) > 0.2)
        assertEquals(0.0, forward.gap(mouth), 1e-9)
        // A saved lot comes back as it was, and one saved before cars had sizes still loads.
        val kept = LotConfig("d", "d", rightSize = CarSize.SMALL, rightStance = Stance.FORWARD)
        assertEquals(kept, LotConfig.decode(kept.encode()))
        assertEquals(LotConfig("e", "e"), LotConfig.decode("e|e|PERPENDICULAR|CAR|CAR|OPEN|6.5|2.5|60.0"))
    }

    @Test
    fun aBadlyPlacedStartIsStillTalkedIn() {
        val spec = Vehicles.default
        val config = Scenarios.byId("facing_cars")!!
        var parked = 0
        var tried = 0
        for (dx in listOf(-0.6, 0.0, 0.7)) {
            for (dy in listOf(-0.8, 0.0, 0.8)) {
                for (dh in listOf(-12.0, 0.0, 12.0)) {
                    tried++
                    val attempt = attemptFrom(spec, config) { _, planner ->
                        val setup = planner.setup(true)!!
                        val moved = setup.compose(Pose(dx, dy, Math.toRadians(dh)))
                        if (planner.free(moved)) moved else setup
                    }
                    if (attempt != null && attempt.success) parked++
                }
            }
        }
        assertEquals(tried, parked)
    }

    @Test
    fun aLaneTooNarrowForOneSwingGetsExtraMoves() {
        val spec = Vehicles.byId("largesuv")!!
        val config = LotConfig("t", "t", opposite = Opposite.WALL, aisle = 5.0)
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(config)
        val lot = engine.lot!!
        val planner = Planner(spec, lot)
        // The green box still exists, but one swing from it is not enough.
        val start = planner.setup(true)!!
        assertNull(planner.direct(start))
        val plan = planner.plan(start)
        assertNotNull(plan)
        assertTrue(plan!!.size >= 3)
        engine.teleport(start)
        engine.startAttempt()
        val car = SimCar(spec)
        car.gear = Gear.DRIVE
        val attempt = drive(engine, car)
        assertNotNull(attempt)
        assertTrue("$attempt", attempt!!.success)
    }

    @Test
    fun drivingOutAndBackInIsOneCleanLoop() {
        val spec = Vehicles.byId("compact")!!
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("basic")!!)
        assertEquals(Phase.READY, engine.phase)
        val car = SimCar(spec)
        val script = Script(spec, Planner(spec, engine.lot!!).exit(true)!!)
        var t = 0.0
        var ds = 0.0
        while (t < 240.0 && engine.phase != Phase.FINISHED && engine.phase != Phase.CRASHED) {
            val hint = if (!script.finished) script.update(ds, car.speed, car.gear, car.wheelDeg) else engine.hint
            car.follow(hint)
            car.step(dt)
            val before = engine.pose
            engine.step(dt, car.speed, car.wheelDeg, car.gear)
            ds = (engine.pose.pos - before.pos).dot(before.fwd)
            t += dt
        }
        assertEquals(Phase.FINISHED, engine.phase)
        val attempt = engine.last!!
        assertTrue("$attempt", attempt.success)
        assertEquals(3, attempt.stars)
        assertTrue(attempt.stats.moves <= 2)
    }

    @Test
    fun touchingAVirtualCarEndsTheAttempt() {
        val spec = Vehicles.default
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("facing_wall")!!)
        var t = 0.0
        while (t < 30.0 && engine.phase != Phase.CRASHED) {
            engine.step(dt, 1.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertEquals(Phase.CRASHED, engine.phase)
        assertEquals(Kind.WALL, engine.hit!!.kind)
        assertTrue(engine.last!!.crashed)
        assertEquals(0, engine.last!!.stars)
    }

    @Test
    fun aFirstAttemptIsRecordedUntilPark() {
        val engine = Engine(Vehicles.default)
        engine.startRecording()
        fun run(seconds: Double, gear: Gear, wheel: Double) {
            var t = 0.0
            while (t < seconds) {
                engine.step(dt, 1.0, wheel, gear)
                t += dt
            }
        }
        run(4.0, Gear.DRIVE, 0.0)
        run(3.0, Gear.REVERSE, 300.0)
        run(2.0, Gear.DRIVE, -200.0)
        run(3.0, Gear.REVERSE, 0.0)
        engine.step(dt, 0.0, 0.0, Gear.PARK)
        assertEquals(Phase.IDLE, engine.phase)
        val stats = DriveStats.of(engine.baseline!!)
        assertEquals(4, stats.moves)
        assertEquals(12.0, stats.distance, 0.3)
    }

    /** Straight out of the space and straight back in, with no help: a first drive to go by. */
    private fun outAndBack(engine: Engine, metres: Double) {
        var t = 0.0
        while (t < metres) {
            engine.step(dt, 1.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertEquals(Phase.PARKING, engine.phase)
        t = 0.0
        while (t < metres) {
            engine.step(dt, 1.0, 0.0, Gear.REVERSE)
            t += dt
        }
        engine.step(dt, 0.0, 0.0, Gear.PARK)
        assertEquals(Phase.FINISHED, engine.phase)
    }

    @Test
    fun guidanceKeepsToGroundTheCarHasBeenOn() {
        val spec = Vehicles.default
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(LotConfig("t", "t", left = Side.EMPTY, right = Side.EMPTY, aisle = 9.0))
        // Nothing to go by yet: the first drive is the driver's own.
        assertNull(engine.limit)
        outAndBack(engine, 6.2)
        val lot = engine.lot!!
        // 6.2 m forward from a space the car left a little room in front of.
        assertEquals(6.2 + spec.length / 2 - lot.slot.length / 2, engine.proven, 0.15)
        assertNotNull(engine.limit)
        val planner = Planner(spec, lot, keepOut = { engine.limit })
        assertTrue(planner.free(lot.target.compose(Pose(3.0, 0.0, 0.0))))
        assertFalse(planner.free(lot.target.compose(Pose(8.0, 0.0, 0.0))))
        // Going further out makes that ground known too.
        engine.again()
        var t = 0.0
        while (t < 8.0) {
            engine.step(dt, 1.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertTrue(engine.proven > 7.5)
        // An open space has nothing to keep inside.
        engine.bounded = false
        assertNull(engine.limit)
    }

    @Test
    fun aRecordedFirstParkingShowsHowMuchLaneThereIs() {
        val spec = Vehicles.default
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.startRecording()
        var t = 0.0
        while (t < 5.0) {
            engine.step(dt, 1.0, 0.0, Gear.REVERSE)
            t += dt
        }
        engine.step(dt, 0.0, 0.0, Gear.PARK)
        assertNotNull(engine.baseline)
        engine.setTarget(LotConfig(Scenarios.REAL, "real", opposite = Opposite.CARS, aisle = 7.0))
        val lot = engine.lot!!
        // The car came 5 m straight back into the space, so that is all of the lane it has seen.
        assertEquals(5.0 + spec.length / 2 - lot.slot.length / 2, engine.proven, 0.15)
        assertEquals(engine.proven + Engine.LANE_SPARE, lot.config.aisle, 0.01)
        assertNotNull(engine.limit)
    }

    @Test
    fun realConesAreKeptClearOfAndMeasured() {
        val spec = Vehicles.default
        val engine = Engine(spec)
        engine.cones = true
        engine.teleport(origin)
        engine.setTarget(LotConfig("t", "t", left = Side.EMPTY, right = Side.EMPTY, opposite = Opposite.CARS, aisle = 6.0))
        val lot = engine.lot!!
        assertEquals(2, lot.obstacles.count { it.kind == Kind.CONE })
        val from = Planner(spec, lot).setup(toLeft = true)
        assertNotNull("no way in past the cones", from)
        engine.teleport(from!!)
        engine.startAttempt()
        val car = SimCar(spec)
        car.gear = Gear.DRIVE
        val attempt = drive(engine, car)
        assertNotNull(attempt)
        assertTrue(attempt!!.success)
        assertEquals(2, attempt.cones.size)
        assertTrue(attempt.cones.all { it > 0.05 && it < 1.0 })
    }

    @Test
    fun theCarsOwnDimensionsMakeASpec() {
        val spec = Vehicles.fromDimensions("X", 4630, 1890, 3000, 5990)!!
        assertEquals(3.0, spec.wheelbase, 1e-9)
        assertTrue(spec.minRadius > 3.5 && spec.minRadius < 5.5)
        assertNull(Vehicles.fromDimensions("X", 0, 0, 0, 0))
        // The Pleos emulator gives the turning circle's diameter where the radius belongs: 10.4 m for a
        // 3 m wheelbase. Read as a radius the car could hardly turn; halved it turns like a car.
        val emulator = Vehicles.fromDimensions("Connect", 5000, 2000, 3000, 10400)!!
        assertTrue(emulator.minRadius > 3.0 && emulator.minRadius < 4.5)
        val config = Scenarios.byId("end_wall")!!
        assertEquals(config, LotConfig.decode(config.encode()))
    }

    @Test
    fun theCoachNamesTheHabitNotJustTheResult() {
        val spec = Vehicles.default
        val config = Scenarios.byId("basic")!!
        // A tidy attempt from the right place earns no complaint.
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.setTarget(config)
        engine.teleport(Planner(spec, engine.lot!!).setup(true)!!)
        engine.startAttempt()
        val car = SimCar(spec)
        car.gear = Gear.DRIVE
        drive(engine, car)
        assertEquals(listOf(Advice(Tip.CLEAN)), engine.advice)

        // Driving two metres too far past the space before reversing is noticed and measured.
        val late = Engine(spec)
        late.teleport(origin)
        late.setTarget(config)
        late.teleport(Planner(spec, late.lot!!).setup(true)!!.compose(Pose(2.0, 0.0, 0.0)))
        late.startAttempt()
        val second = SimCar(spec)
        second.gear = Gear.DRIVE
        drive(late, second)
        val tip = late.advice.first { it.tip == Tip.STARTED_LATE }
        assertEquals(2.0, tip.amount, 0.3)
    }

    @Test
    fun theSensorDistanceShrinksAsTheCarNearsAWall() {
        val a = Box(Vec(0.0, 0.0), 0.0, 4.0, 2.0)
        assertEquals(1.0, a.distance(Box(Vec(5.0, 0.0), 0.0, 4.0, 2.0)), 1e-9)
        assertEquals(0.0, a.distance(Box(Vec(3.0, 0.0), 0.0, 4.0, 2.0)), 1e-9)
        assertEquals(0.5, a.gap(Vec(0.0, 1.5)), 1e-9)
        val engine = Engine(Vehicles.default)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("facing_wall")!!)
        val parked = engine.clearance
        engine.step(dt, 0.0, 0.0, Gear.PARK)
        assertTrue(parked == Double.MAX_VALUE || parked > 0.0)
        var before = Double.MAX_VALUE
        var t = 0.0
        while (t < 3.0) {
            engine.step(dt, 1.0, 0.0, Gear.DRIVE)
            t += dt
        }
        before = engine.clearance
        while (t < 4.0) {
            engine.step(dt, 1.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertTrue(engine.clearance < before)
    }

    @Test
    fun rollingPastTheMarkIsToldToStopNotToKeepGoing() {
        for (id in listOf("basic", "nose_in")) {
            val config = Scenarios.byId(id)!!
            val engine = Engine(Vehicles.default)
            engine.teleport(origin)
            engine.setTarget(config)
            val lot = engine.lot!!
            val into = if (config.type == SlotType.NOSE_IN) 1 else -1
            val gear = if (into > 0) Gear.DRIVE else Gear.REVERSE
            // Lined up two metres out, then rolling straight in without stopping.
            engine.teleport(lot.target.compose(Pose(-into * 2.0, 0.0, 0.0)))
            engine.startAttempt()
            repeat(30) { engine.step(dt, 0.0, 0.0, gear) }
            var past = -2.0
            while (past < 0.2 && engine.phase == Phase.PARKING) {
                engine.step(dt, 0.3, 0.0, gear)
                past = into * lot.target.relative(engine.pose).x
                if (past > Guide.STOP_WITHIN) assertEquals("$id, ${past}m past the mark", Cue.STOP, engine.hint?.cue)
            }
        }
    }

    @Test
    fun theDemoCarsDriveOutAndBackTouchesNothing() {
        // What "시범 보기" does: the demo car leaves by a planned way out, then follows the guide back in.
        // Into a nose-first bay the guide once took what was left of an old plan for the last straight
        // and steered the car into the next one.
        for (spec in Vehicles.presets) {
            for (config in Scenarios.all) {
                val random = kotlin.random.Random(1)
                val engine = Engine(spec)
                engine.bounded = false
                engine.teleport(origin)
                engine.setTarget(config)
                val planner = Planner(spec, engine.lot!!)
                val left = random.nextBoolean()
                val exit = planner.wander(left, random) ?: planner.wander(!left, random) ?: continue
                val script = Script(spec, exit)
                val car = SimCar(spec)
                var lastDs = 0.0
                var t = 0.0
                while (t < 180 && engine.phase != Phase.FINISHED && engine.phase != Phase.CRASHED) {
                    val signed = if (car.gear == Gear.REVERSE) -car.speed else car.speed
                    car.follow(if (!script.finished) script.update(lastDs, signed, car.gear, car.wheelDeg) else engine.hint)
                    car.step(dt)
                    val from = engine.pose
                    engine.step(dt, car.speed, car.wheelDeg, car.gear)
                    lastDs = (engine.pose.pos - from.pos).dot(from.fwd)
                    t += dt
                }
                assertFalse("${spec.id} touched ${engine.hit?.kind} in ${config.id}", engine.phase == Phase.CRASHED)
            }
        }
    }

    @Test
    fun anotherGoStraightAfterACrashIsNotAnotherCrash() {
        for (id in listOf("basic", "end_wall", "garage", "parallel")) {
            val spec = Vehicles.default
            val engine = Engine(spec)
            engine.teleport(origin)
            engine.setTarget(Scenarios.byId(id)!!)
            engine.teleport(Planner(spec, engine.lot!!).setup(true)!!)
            engine.startAttempt()
            var t = 0.0
            while (t < 30 && engine.phase == Phase.PARKING) {
                engine.step(dt, 0.5, -500.0, Gear.REVERSE)
                t += dt
            }
            assertEquals(Phase.CRASHED, engine.phase)
            // "다시 연습" while still on top of what was hit: standing there is not a new touch.
            engine.again()
            repeat(30) { engine.step(dt, 0.0, -500.0, Gear.REVERSE) }
            assertEquals("$id: crashed again standing still", Phase.PARKING, engine.phase)
            assertEquals(1, engine.history.size)
            // Once clear, a touch counts again.
            while (t < 60 && engine.lot!!.hits(spec.body(engine.pose)) != null) {
                engine.step(dt, 0.3, 0.0, Gear.DRIVE)
                t += dt
            }
            while (t < 90 && engine.phase == Phase.PARKING) {
                engine.step(dt, 0.5, -500.0, Gear.REVERSE)
                t += dt
            }
            assertEquals("$id: a new touch went unnoticed", Phase.CRASHED, engine.phase)
        }
    }

    @Test
    fun withoutTheSteeringAngleNothingIsTrackedOrJudged() {
        val engine = Engine(Vehicles.default)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("basic")!!)
        engine.blind = true
        // Standing still while blind: the space stays, nothing happens.
        repeat(30) { engine.step(dt, 0.0, 0.0, Gear.PARK) }
        assertEquals(Phase.READY, engine.phase)
        assertEquals(origin, engine.pose)
        // Moving while blind: the path cannot be known, so the practice is given up rather than guessed.
        engine.step(dt, 1.0, 0.0, Gear.DRIVE)
        assertEquals(origin, engine.pose)
        assertEquals(Phase.IDLE, engine.phase)
        assertEquals(Drop.BLIND, engine.dropped)
        assertNull(engine.lot)
    }

    @Test
    fun drivingAwayEndsThePractice() {
        val engine = Engine(Vehicles.default)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("basic")!!)
        var t = 0.0
        while (t < 60 && engine.phase != Phase.IDLE) {
            engine.step(dt, 3.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertEquals(Phase.IDLE, engine.phase)
        assertEquals(Drop.FAR, engine.dropped)
        assertTrue("given up at ${t * 3.0} m", t * 3.0 < Engine.GIVE_UP + 10.0)
        assertTrue(engine.history.isEmpty())
    }

    @Test
    fun almostInIsTalkedTheRestOfTheWay() {
        // Two places the guide used to give up on: a wide car within the margin of its neighbour in a
        // tight space (it sent the driver to the green box, 8 m away), and a van 10 cm short and
        // crooked along a kerb (it said "stop" and nothing else, for ever).
        val cases = listOf(
            Triple(Vehicles.byId("ev")!!, "tight_slot", Pose(-0.02, 0.09, Math.toRadians(4.0))),
            Triple(Vehicles.byId("van")!!, "parallel_tight", Pose(-0.10, 0.08, Math.toRadians(4.8))),
        )
        for ((spec, id, offset) in cases) {
            val engine = Engine(spec)
            engine.teleport(origin)
            engine.setTarget(Scenarios.byId(id)!!)
            val lot = engine.lot!!
            val start = lot.target.compose(offset)
            assertNull("$id starts touching", lot.hits(spec.body(start)))
            engine.teleport(start)
            engine.startAttempt()
            repeat(30) { engine.step(dt, 0.0, 0.0, Gear.DRIVE) }
            val hint = engine.hint!!
            assertTrue("$id: ${hint.cue}", hint.cue != Cue.FIND_SPOT && hint.cue != Cue.STOP)
            val car = SimCar(spec)
            car.gear = Gear.DRIVE
            val attempt = drive(engine, car, 120.0)
            assertTrue("$id never parked: ${engine.phase} ${engine.hint?.cue}", attempt?.success == true)
        }
    }

    @Test
    fun theDemoCarCanShowItAgainAndAgain() {
        // "시범 보기" pressed run after run in a car park, the way AppCore.autopilot drives: from the mark
        // (it is no real car), and not held to the ground it has been on (it has no real lane).
        for (spec in Vehicles.presets) {
            for (config in Scenarios.all) {
                val random = kotlin.random.Random(1)
                val engine = Engine(spec)
                engine.teleport(origin)
                engine.setTarget(config)
                val car = SimCar(spec)
                for (run in 1..3) {
                    assertEquals("${spec.id} ${config.id} run $run", Phase.READY, engine.phase)
                    engine.reanchor()
                    val planner = Planner(spec, engine.lot!!)
                    val left = random.nextBoolean()
                    val exit = planner.wander(left, random) ?: planner.wander(!left, random) ?: break
                    val script = Script(spec, exit)
                    var lastDs = 0.0
                    var t = 0.0
                    while (t < 180 && engine.phase != Phase.FINISHED && engine.phase != Phase.CRASHED) {
                        val signed = if (car.gear == Gear.REVERSE) -car.speed else car.speed
                        car.follow(if (!script.finished) script.update(lastDs, signed, car.gear, car.wheelDeg) else engine.hint)
                        car.step(dt)
                        engine.selfDriven = true
                        val from = engine.pose
                        engine.step(dt, car.speed, car.wheelDeg, car.gear)
                        lastDs = (engine.pose.pos - from.pos).dot(from.fwd)
                        t += dt
                    }
                    assertTrue("${spec.id} ${config.id} run $run: ${engine.phase} ${engine.hint?.cue}", engine.last?.success == true && engine.phase == Phase.FINISHED)
                    engine.again()
                }
            }
        }
    }

    @Test
    fun parkBeforeMovingIsNotAnAttempt() {
        val engine = Engine(Vehicles.default)
        engine.teleport(origin)
        engine.setTarget(Scenarios.byId("basic")!!)
        // Out of the space, stopped, and into P at once (to read the screen, or on the result screen
        // with "다시 연습"): no attempt of no moves is recorded.
        var t = 0.0
        while (t < 10 && engine.phase != Phase.PARKING) {
            engine.step(dt, if (engine.lot!!.slot.intersects(Vehicles.default.body(engine.pose))) 1.0 else 0.0, 0.0, Gear.DRIVE)
            t += dt
        }
        assertEquals(Phase.PARKING, engine.phase)
        repeat(60) { engine.step(dt, 0.0, 0.0, Gear.PARK) }
        assertEquals(Phase.PARKING, engine.phase)
        assertTrue(engine.history.isEmpty())
        // Driving and then selecting P outside the space still ends the attempt, as a miss.
        repeat(30) { engine.step(dt, 0.5, 0.0, Gear.REVERSE) }
        repeat(5) { engine.step(dt, 0.0, 0.0, Gear.PARK) }
        assertEquals(Phase.FINISHED, engine.phase)
        assertFalse(engine.last!!.success)
        // "다시 연습" from there, still in P: the practice goes on, nothing new is recorded.
        engine.again()
        repeat(60) { engine.step(dt, 0.0, 0.0, Gear.PARK) }
        assertEquals(Phase.PARKING, engine.phase)
        assertEquals(1, engine.history.size)
    }

    @Test
    fun withNoGreenBoxTheGuideDoesNotKeepLooking() {
        // A van in a narrow lane, with only a metre of lane known to be clear: there is no way in and
        // no green box. Looking for one took half a second, and it was looked for again every frame.
        val spec = Vehicles.byId("van")!!
        val lot = LotBuilder.build(Scenarios.byId("narrow_aisle")!!, spec, Pose(0.0, 0.0, PI / 2))
        val keep = lot.strip(1.0)
        var looks = 0
        val planner = Planner(spec, lot, keepOut = { looks++; keep })
        val guide = Guide(spec, planner, lot)
        val standing = lot.target.compose(Pose(spec.centerAhead + 0.3, 0.0, 0.3))
        var hint: Hint? = null
        repeat(30) { hint = guide.update(standing, 0.0, dt, 0.0, Gear.DRIVE, 0.0) }
        assertEquals(Cue.FIND_SPOT, hint?.cue)
        assertNull(hint?.box)
        val before = looks
        repeat(60) { guide.update(standing, 0.0, dt, 0.0, Gear.DRIVE, 0.0) }
        assertEquals("searched again while standing still", before, looks)
    }

    @Test
    fun aPlanNeverAsksForMoreWheelThanTheCarHas() {
        // The Pleos emulator: 5.0 m long, 3.0 m wheelbase, a 10.4 m turning figure, and a wheel that stops at 500°.
        val worked = Vehicles.fromDimensions("에뮬레이터", 5000, 1900, 3000, 10400)!!
        assertTrue(worked.lockDeg > 600.0)
        val spec = worked.limitedTo(500.0)
        assertEquals(500.0, spec.lockDeg, 1e-9)
        assertEquals(spec, spec.limitedTo(null))
        assertEquals(spec, spec.limitedTo(720.0))
        val wheel = spec.copy() // the real wheel, which goes no further
        for (config in Scenarios.all) {
            val engine = Engine(spec)
            engine.teleport(origin)
            engine.setTarget(config)
            val lot = engine.lot!!
            val from = Planner(spec, lot).setup(toLeft = config.left != Side.WALL)
                ?: lot.target.compose(Pose(spec.centerAhead + lot.slot.length / 2 + config.aisle / 2, -3.0, -PI / 2))
            engine.teleport(from)
            engine.startAttempt()
            val car = SimCar(wheel)
            car.gear = Gear.DRIVE
            var asked = 0.0
            var t = 0.0
            while (t < 240.0 && engine.phase == Phase.PARKING) {
                engine.hint?.let { asked = maxOf(asked, abs(it.targetWheelDeg)) }
                car.follow(engine.hint)
                car.step(dt)
                engine.step(dt, car.speed, car.wheelDeg, car.gear)
                t += dt
            }
            assertTrue("asked for ${asked.toInt()}° in ${config.id}", asked <= 500.0)
            assertTrue("did not park in ${config.id}: ${engine.phase}", engine.last?.success == true)
        }
    }

    /**
     * A drive that ends parked in [lot]: along the lane to the green box, then the moves the planner
     * gives from there. It is what a driver who parks well would have recorded.
     */
    private fun driveIn(spec: VehicleSpec, lot: Lot, toLeft: Boolean, lane: Double = 8.0): List<Sample>? {
        val planner = Planner(spec, lot)
        val box = planner.setup(toLeft) ?: return null
        val moves = planner.direct(box) ?: return null
        val out = ArrayList<Sample>()
        var t = 0.0
        var p = box.compose(Pose(-lane, 0.0, 0.0))
        fun roll(dir: Int, road: Double, dist: Double) {
            var left = dist
            while (left > 1e-6) {
                val d = minOf(0.1, left)
                p = Motion.advance(p, dir * d, road, spec.wheelbase)
                t += 0.1
                out.add(Sample(t, p, if (dir > 0) Gear.DRIVE else Gear.REVERSE, 0.0))
                left -= d
            }
        }
        out.add(Sample(0.0, p, Gear.DRIVE, 0.0))
        roll(1, 0.0, lane)
        for (m in moves) roll(m.dir, m.road, m.dist)
        return out
    }

    @Test
    fun aRecordedDriveSaysWhichWayTheCarWentIn() {
        val spec = Vehicles.default
        val kinds = listOf(
            LotConfig("r", "r"),
            LotConfig("n", "n", type = SlotType.NOSE_IN, aisle = 8.0),
            LotConfig("a60", "a60", type = SlotType.ANGLED, opposite = Opposite.WALL, aisle = 5.5, angle = 60.0),
            LotConfig("a45", "a45", type = SlotType.ANGLED, opposite = Opposite.WALL, aisle = 4.5, angle = 45.0),
            LotConfig("p", "p", type = SlotType.PARALLEL, aisle = 5.0, room = 2.8),
        )
        for (kind in kinds) {
            for (mirror in listOf(false, true)) {
                for (toLeft in listOf(true, false)) {
                    val config = kind.copy(mirror = mirror)
                    val lot = LotBuilder.build(config, spec, origin)
                    val drive = driveIn(spec, lot, toLeft)
                    assertNotNull("no way into ${config.id} mirror=$mirror", drive)
                    val read = Entry.read(drive!!)
                    assertNotNull("nothing read from ${config.id}", read)
                    assertEquals("${config.id} mirror=$mirror toLeft=$toLeft", config.type, read!!.type)
                    if (config.type == SlotType.ANGLED) assertEquals(config.id, config.angle, read.angle, 0.01)
                    // Only a space with a handedness says which way round it lies.
                    if (config.mirrored || config.type == SlotType.PARALLEL || config.type == SlotType.ANGLED) {
                        assertEquals("${config.id} mirror=$mirror toLeft=$toLeft", config.mirrored, read.mirror)
                    }
                    // Laid out the way the drive says, that drive goes through no wall: the lane is where the car came from.
                    val place = LotBuilder.build(read.on(config), spec, origin)
                    assertNull("${config.id} mirror=$mirror: the drive went through a wall", place.through(drive.map { it.pose }, spec))
                }
            }
        }
        // Straight in from the lane, with no turn at all, either end first.
        fun straight(dir: Int): List<Sample> = (0..60).map { Sample(it * 0.1, origin.compose(Pose(-dir * (6.0 - it * 0.1), 0.0, 0.0)), Gear.DRIVE, 0.0) }
        assertEquals(SlotType.NOSE_IN, Entry.read(straight(1))!!.type)
        assertEquals(SlotType.PERPENDICULAR, Entry.read(straight(-1))!!.type)
        // A couple of metres show too little to say anything.
        assertNull(Entry.read(straight(1).takeLast(20)))
    }

    @Test
    fun aPlaceDescribedTheWrongWayRoundIsCaughtByTheDriveRecordedThere() {
        val spec = Vehicles.default
        val engine = Engine(spec)
        engine.teleport(origin)
        engine.startRecording()
        // Nose-first into a bay: along the lane, a quarter turn to the left, then straight in.
        var t = 0.0
        fun run(seconds: Double, wheel: Double) {
            val until = t + seconds
            while (t < until) {
                engine.step(dt, 1.0, wheel, Gear.DRIVE)
                t += dt
            }
        }
        run(6.0, 0.0)
        val lock = -spec.wheelDeg(spec.maxRoad * 0.8)
        val radius = spec.wheelbase / kotlin.math.tan(spec.maxRoad * 0.8)
        run(radius * PI / 2, lock)
        run(3.0, 0.0)
        engine.step(dt, 0.0, 0.0, Gear.PARK)
        val drive = engine.baseline
        assertNotNull(drive)
        val asIs = LotConfig(Scenarios.REAL, "real", opposite = Opposite.WALL)
        // Taken for reversed in, as it used to be without asking, the car came in through the wall behind it.
        assertTrue(engine.misfit(asIs))
        val read = Entry.read(drive!!)
        assertEquals(SlotType.NOSE_IN, read!!.type)
        assertFalse(engine.misfit(read.on(asIs)))
        engine.setTarget(read.on(asIs))
        val lot = engine.lot!!
        // The lane is where the car came from: the start of the drive is out in it, not behind the row.
        assertTrue(lot.reach(spec.body(drive.first().pose)) > 2.0)
        assertNull(lot.through(drive.map { it.pose }, spec))
    }

    @Test
    fun aPlaceTheOtherWayRoundIsTheSameTurnedOver() {
        val spec = Vehicles.default
        val kerbside = LotConfig("p", "p", type = SlotType.PARALLEL, aisle = 5.0, room = 2.8)
        for (mirror in listOf(false, true)) {
            val lot = LotBuilder.build(kerbside.copy(mirror = mirror), spec, origin)
            val kerb = lot.obstacles.first { it.kind == Kind.KERB }
            // The kerb is on the car's right and the road on its left, unless the place is the other way round.
            val side = origin.toLocal(kerb.box.c).y
            assertTrue("mirror=$mirror kerb at $side", if (mirror) side > 1.0 else side < -1.0)
            assertEquals(if (mirror) -1.0 else 1.0, lot.out.dot(origin.left), 1e-6)
        }
        // Square bays are the same either way round.
        val bay = LotConfig("b", "b", left = Side.WALL, rightSize = CarSize.LARGE)
        assertEquals(LotBuilder.build(bay, spec, origin).obstacles, LotBuilder.build(bay.copy(mirror = true), spec, origin).obstacles)
        // A saved place keeps which way round it lies, and one saved before there was such a thing reads as usual.
        assertTrue(LotConfig.decode(kerbside.copy(mirror = true).encode())!!.mirror)
        assertFalse(LotConfig.decode(kerbside.encode().substringBeforeLast("|"))!!.mirror)
    }

    @Test
    fun aPlaceTheOtherWayRoundCanBeParkedInByEveryCar() {
        val handed = Scenarios.all.filter { it.type == SlotType.PARALLEL || it.type == SlotType.ANGLED }
        assertEquals(4, handed.size)
        for (spec in Vehicles.presets) {
            for (config in handed) {
                val attempt = attemptFrom(spec, config.copy(mirror = true)) { _, planner -> planner.setup(toLeft = true) }
                assertNotNull("${spec.id} never finished ${config.id} the other way round", attempt)
                assertTrue("${spec.id} failed ${config.id} the other way round: $attempt", attempt!!.success)
                assertTrue("${spec.id} sat crooked in ${config.id}", abs(attempt.sideways) < 0.3 && attempt.angleDeg < 6.0)
            }
        }
    }
}
