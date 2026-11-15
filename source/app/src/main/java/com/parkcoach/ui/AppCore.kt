package com.parkcoach.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.parkcoach.core.Course
import com.parkcoach.core.Cue
import com.parkcoach.core.Drop
import com.parkcoach.core.Engine
import com.parkcoach.core.Entry
import com.parkcoach.core.Gear
import com.parkcoach.core.Hint
import com.parkcoach.core.LotConfig
import com.parkcoach.core.Phase
import com.parkcoach.core.Place
import com.parkcoach.core.Planner
import com.parkcoach.core.Sample
import com.parkcoach.core.Scenarios
import com.parkcoach.core.Script
import com.parkcoach.core.VehicleSpec
import com.parkcoach.core.Vehicles
import com.parkcoach.vehicle.CarLink
import com.parkcoach.vehicle.DemoCarLink
import com.parkcoach.vehicle.PleosCarLink
import com.parkcoach.vehicle.SteeringUnit
import com.parkcoach.voice.AndroidSpeaker
import com.parkcoach.voice.PleosSpeaker
import com.parkcoach.voice.SilentSpeaker
import com.parkcoach.voice.Speaker

enum class Screen { HOME, SCENARIOS, EDITOR, DRIVE, RESULT, REPLAY, GARAGE, PLACES, SIGNALS, SETTINGS }

/**
 * How much help the driver gets. A coach who never stops talking teaches nothing, so the help is
 * taken away as the driver succeeds and given back after a failure.
 */
enum class Assist { FULL, PATH, SOLO }

/**
 * Level 1 is an empty space in a quiet car park (or, if the driver chooses, an open space) with
 * virtual neighbours around it. Level 2 is the driver's own space, with whatever really stands there.
 */
enum class Level { OPEN, REAL }

data class UiState(
    val screen: Screen = Screen.HOME,
    val level: Level = Level.OPEN,
    val demo: Boolean = false,
    val problem: String? = null,
    val notice: String? = null,
    val spec: VehicleSpec = Vehicles.default,
    /** True when the size came from the car itself rather than from a choice on screen. */
    val specFromCar: Boolean = false,
    val custom: LotConfig = LotConfig(Scenarios.CUSTOM, "내가 만든 주차장"),
    /** The situation picked for level 1, waiting for the car to be placed. */
    val chosen: LotConfig? = null,
    /** The situation being edited: the custom lot, or the answers about a real one. */
    val editing: LotConfig? = null,
    /** Goes up once per drawn frame so the moving picture redraws. */
    val frame: Int = 0,
    val phase: Phase = Phase.IDLE,
    val gear: Gear = Gear.UNKNOWN,
    val autopilot: Boolean = false,
    val assist: Assist = Assist.FULL,
    val voice: Boolean = true,
    /** Best stars so far, by situation. */
    val best: Map<String, Int> = emptyMap(),
    val replay: List<Sample>? = null,
    val replayTitle: String = "",
    val replayT: Double = 0.0,
    /** Lessons whose test has been passed: parked alone, with [Course.PASS] stars or more. */
    val passed: Set<String> = emptySet(),
    /** How much help each lesson has got down to. */
    val stages: Map<String, Assist> = emptyMap(),
    /** Every lesson can be opened, whatever has been passed. */
    val openAll: Boolean = false,
    /** Places saved for practising at. */
    val places: List<Place> = emptyList(),
    /** Level 1 is being done in an open space with no lines, rather than in a car park. */
    val openSpace: Boolean = false,
    /** Two real cones stand at the front corners of the space. */
    val cones: Boolean = false,
    /** While the car moves the picture dims, so the driver looks at the mirrors and not the screen. */
    val eyesOut: Boolean = true,
    /** The signal the car has not reported yet, by name; guidance waits for it. */
    val missing: String? = null,
    /** The car reports a turn to the right as a negative angle. */
    val rightNegative: Boolean = true,
    val steeringUnit: SteeringUnit = SteeringUnit.AUTO,
    /** The driver has read and agreed to the safety check since the app was opened. */
    val checked: Boolean = false,
    /** The driver has yet to be told why the car's speed is asked for, and then asked. */
    val carAsk: Boolean = false,
    /** The driver's door is open: practice waits. */
    val doorOpen: Boolean = false,
    /**
     * While a real place is being described: whether it is known how the car went in. That decides
     * which side of the car the lane is drawn on, so nothing is laid out until it is.
     */
    val entryKnown: Boolean = true,
    /** It is known from the first parking recorded there, not because the driver said so. */
    val entryRead: Boolean = false,
    /** A small window is wanted over the rear camera while reversing. */
    val floating: Boolean = true,
    /** The driver has allowed the app to draw over other apps, which that window needs. */
    val floatAllowed: Boolean = false,
)

/**
 * Joins the car, the trainer and the screen. One frame loop reads the car, steps the engine and,
 * for the demo car, plays the driver.
 */
class AppCore(private val app: Context) {

    var state by mutableStateOf(UiState())
        private set

    val engine = Engine(Vehicles.default)

    private val prefs = app.getSharedPreferences("parkcoach", Context.MODE_PRIVATE)
    private var link: CarLink? = null
    private var demoLink: DemoCarLink? = null
    private var speaker: Speaker = SilentSpeaker
    private var started = false
    private var lastNanos = 0L
    private var aliveCheckAt = 0L
    private var specPinned = false
    private var script: Script? = null
    private var lastDs = 0.0

    /** When, on the trainer's clock, the voice last said what to do with the wheel. */
    private var spokenAt = 0.0
    private var spokenCue: String = ""
    private var spokenMark = Double.MAX_VALUE
    private var afterReplay: Screen = Screen.RESULT
    private var tone: android.media.ToneGenerator? = null
    private var sinceBeep = 0.0

    /** The small window that stays up while the rear camera has the screen. */
    private val floatWindow = FloatingWindow(app, this)
    private var foreground = true
    private var appArea = android.graphics.Rect()
    private var floatClosed = false

    /** When, on the trainer's clock, putting the small window up may next be tried after it failed. */
    private var floatRetryAt = 0.0

    /** Until when, on the trainer's clock, the small window is being tried out over the app itself. */
    private var trialUntil = 0.0

    // ------------------------------------------------------------------ start-up

    /**
     * Loads what was saved. [connectNow] is false while the driver is still being asked for the
     * car permissions: the car is connected when they have answered, by [reconnect].
     */
    fun start(connectNow: Boolean = true) {
        if (started) return
        started = true
        val saved = decodeSpec(prefs.getString(KEY_SPEC, null))
        specPinned = saved != null
        val custom = LotConfig.decode(prefs.getString(KEY_CUSTOM, "") ?: "") ?: state.custom
        val best = HashMap<String, Int>()
        for (config in Scenarios.all + custom) {
            val stars = prefs.getInt(KEY_BEST + config.id, 0)
            if (stars > 0) best[config.id] = stars
        }
        Palette.light = prefs.getBoolean(KEY_LIGHT, true)
        floatWindow.resize(prefs.getInt(KEY_FLOAT_SIZE, 1))
        val passed = Scenarios.all.map { it.id }.filter { prefs.getBoolean(KEY_PASS + it, false) }.toSet()
        val stages = Scenarios.all.map { it.id }.filter { prefs.contains(KEY_ASSIST + it) }.associateWith { loadAssist(it) }
        val places = (prefs.getString(KEY_PLACES, "") ?: "").split("\n").mapNotNull { Place.decode(it) }
        state = state.copy(
            spec = saved ?: Vehicles.default, custom = custom, best = best, voice = prefs.getBoolean(KEY_VOICE, true),
            passed = passed, stages = stages, openAll = prefs.getBoolean(KEY_OPEN_ALL, false), places = places,
            openSpace = prefs.getBoolean(KEY_OPEN_SPACE, false), cones = prefs.getBoolean(KEY_CONES, false),
            eyesOut = prefs.getBoolean(KEY_EYES_OUT, true), rightNegative = prefs.getBoolean(KEY_RIGHT_NEGATIVE, true),
            floating = prefs.getBoolean(KEY_FLOATING, true), floatAllowed = floatWindow.allowed,
            steeringUnit = steeringUnit(),
        )
        fitSpec(state.spec)
        engine.bounded = !state.openSpace
        engine.cones = state.cones
        if (connectNow) connect()
        pulse.post(beat)
    }

    private fun connect() {
        val automotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        if (automotive && !prefs.getBoolean(KEY_FORCE_DEMO, false)) {
            try {
                val pleos = PleosCarLink(app, prefs.getBoolean(KEY_RIGHT_NEGATIVE, true), steeringUnit())
                pleos.start(state.spec) { problem -> state = state.copy(problem = problem) }
                link = pleos
                // Every reconnect (permissions answered, steering settings changed) used to make a new
                // speaker and never let the old one go: two speech services bound, the old one still talking.
                if (speaker !is PleosSpeaker) {
                    speaker.release()
                    speaker = makeSpeaker(true)
                }
                // Long enough for the SDK to reach the car's service on a cold emulator.
                aliveCheckAt = System.nanoTime() + 10_000_000_000L
                state = state.copy(demo = false)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Pleos Vehicle SDK is not usable here", e)
            }
        }
        useDemo(null)
    }

    private fun useDemo(problem: String?) {
        try {
            link?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "Stopping the car link failed: ${e.message}")
        }
        val demo = DemoCarLink(state.spec)
        demo.start(state.spec) { }
        link = demo
        demoLink = demo
        if (speaker === SilentSpeaker) speaker = makeSpeaker(false)
        state = state.copy(demo = true, problem = problem)
    }

    /** Called once the driver has answered the permission questions, and to try the car again. */
    fun reconnect() {
        demoLink = null
        aliveCheckAt = 0L
        try {
            link?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "Stopping the car link failed: ${e.message}")
        }
        link = null
        connect()
    }

    private fun makeSpeaker(pleosFirst: Boolean): Speaker {
        if (pleosFirst) {
            try {
                return PleosSpeaker(app)
            } catch (e: Throwable) {
                Log.w(TAG, "Pleos TTS is not usable here", e)
            }
        }
        return try {
            AndroidSpeaker(app)
        } catch (e: Throwable) {
            SilentSpeaker
        }
    }

    private fun say(text: String) {
        if (state.voice && text.isNotBlank()) speaker.speak(text)
    }

    // ------------------------------------------------------------------ the frame loop

    /** Seconds since the screen began to draw, for anything on it that loops. */
    var clock: Double = 0.0
        private set

    /** Called for every frame drawn, which keeps the picture moving. The work is in [pump]. */
    fun onFrame(nanos: Long) = pump()

    /** The trainer's own clock, so that it goes on reading the car when no frame is being drawn. */
    private val pulse = android.os.Handler(android.os.Looper.getMainLooper())
    private val beat = object : Runnable {
        override fun run() {
            pump()
            pulse.postDelayed(this, PULSE_MS)
        }
    }

    /**
     * Reads the car and moves the trainer on by however much time has really passed.
     *
     * Distance is speed times time. Counted per frame drawn, a display that draws slowly (an
     * emulator under load) or not at all (the rear camera taking the screen in reverse) loses
     * distance, and the car on the map falls behind the real one. So the time is taken from the
     * clock, this runs on a timer of its own as well as on every frame, and a long gap is walked
     * through in short steps so that a turn stays a turn.
     */
    private fun pump() {
        val car = link ?: return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, MAX_GAP)
        lastNanos = now
        clock += dt

        // A car that never reports anything is not really there: carry on with the demo car.
        if (demoLink == null && aliveCheckAt != 0L && now > aliveCheckAt) {
            aliveCheckAt = 0L
            if (!car.alive) {
                useDemo("차량 신호를 받지 못해 데모 차량으로 실행합니다")
                return
            }
        }
        adoptCarSpec(car)
        // The wheel's range can arrive after the size: the plan then has to come within it.
        fitSpec(state.spec)

        val demo = demoLink
        val before = engine.phase
        // An angle or a speed that has not arrived is not zero: without both, the path would be a guess.
        val missing = if (!car.steeringKnown) "핸들 각도" else if (!car.speedKnown) "차 속도" else null
        engine.blind = missing != null
        val door = car.doorOpen
        if (door != engine.doorOpen) {
            engine.doorOpen = door
            if (door && state.screen == Screen.DRIVE) say("문이 열렸습니다. 닫을 때까지 안내를 멈춥니다.")
        }
        // The demo car driving itself has no real lane to keep to (see Engine.selfDriven).
        engine.selfDriven = demo != null && state.autopilot
        var left = dt
        do {
            val h = kotlin.math.min(left, STEP)
            if (demo != null) {
                if (state.autopilot) autopilot(demo)
                demo.sim.step(h)
            }
            val from = engine.pose
            engine.step(h, car.speed, car.wheelDeg, car.gear)
            lastDs = (engine.pose.pos - from.pos).dot(from.fwd)
            left -= h
        } while (left > 1e-6)
        if (engine.phase != before) onPhase(before, engine.phase)
        if (state.assist == Assist.FULL) speak(engine.hint)
        sensorBeep(dt, car.speed)
        float(car.gear)

        var next = state
        val samples = next.replay
        if (next.screen == Screen.REPLAY && samples != null && samples.isNotEmpty()) {
            val end = samples.last().t + 1.5
            val t = next.replayT + dt * REPLAY_SPEED
            next = next.copy(replayT = if (t > end) samples.first().t else t)
        }
        val live = next.screen == Screen.DRIVE || next.screen == Screen.REPLAY || next.screen == Screen.SIGNALS
        if (live || next.phase != engine.phase || next.gear != engine.gear || next.doorOpen != door || next.missing != missing) {
            next = next.copy(frame = next.frame + 1, phase = engine.phase, gear = engine.gear, doorOpen = door, missing = missing)
        }
        if (next != state) state = next
    }

    /** Uses the car's own description of itself unless the driver has chosen a size by hand. */
    private fun adoptCarSpec(car: CarLink) {
        if (specPinned || state.specFromCar) return
        val vin = car.vin
        val remembered = if (vin != null) decodeSpec(prefs.getString(KEY_SPEC + vin, null)) else null
        val found = remembered ?: car.detected ?: return
        state = state.copy(spec = found, specFromCar = true)
        fitSpec(found)
    }

    /**
     * Puts [spec] to use, with no more lock than the car says its wheel has. The screen shows the
     * same figure the engine plans with, so a hand-picked lock beyond the car's range comes back down.
     */
    private fun fitSpec(spec: VehicleSpec) {
        link?.useSpec(spec)
        val fit = spec.limitedTo(link?.wheelLimit)
        if (fit != state.spec) state = state.copy(spec = fit)
        if (fit != engine.spec) engine.changeSpec(fit)
    }

    private fun onPhase(from: Phase, to: Phase) {
        when (to) {
            Phase.PARKING -> {
                spokenCue = ""
                say("주차를 시작합니다")
            }
            Phase.FINISHED -> {
                val attempt = engine.last ?: return
                val id = engine.lot?.config?.id ?: ""
                // A drive the demo car did by itself is not the driver's: no stars, no pass, no step in help.
                val shown = state.autopilot
                val best = state.best[id] ?: 0
                if (!shown && attempt.stars > best) {
                    prefs.edit().putInt(KEY_BEST + id, attempt.stars).apply()
                    state = state.copy(best = state.best + (id to attempt.stars))
                }
                // The test: parked alone, well enough. Passing it opens the next lesson.
                val alone = state.assist == Assist.SOLO
                val passedNow = !shown && alone && attempt.success && attempt.stars >= Course.PASS && id in Course.order && id !in state.passed
                stopAutopilot()
                state = state.copy(screen = Screen.RESULT, notice = null)
                // A drive the demo car did by itself proves nothing about the driver.
                if (firstTry() && !shown && !passedNow) {
                    // On to the help this place had reached before, which is full help the first time.
                    val kept = loadAssist(id)
                    state = state.copy(
                        assist = kept,
                        notice = if (kept == Assist.FULL) "첫 주차를 기억했어요. 이제 더 나은 길을 안내합니다" else "첫 주차를 기억했어요. 이어서 '${Texts.assistName(kept)}'로 합니다",
                    )
                } else if (!shown) {
                    adaptAssist(id, attempt.stars >= 2)
                }
                if (passedNow) {
                    prefs.edit().putBoolean(KEY_PASS + id, true).apply()
                    val next = Course.after(id)?.let { Scenarios.byId(it) }
                    state = state.copy(
                        passed = state.passed + id,
                        notice = if (next != null) "시험 통과! 다음 단계가 열렸어요: ${next.name}" else "시험 통과! 코스를 모두 마쳤어요",
                    )
                }
                say(if (passedNow) "시험 통과. 별 ${attempt.stars}개." else Texts.verdictSpeech(attempt))
            }
            Phase.CRASHED -> {
                val shown = state.autopilot
                stopAutopilot()
                state = state.copy(screen = Screen.RESULT, notice = null)
                // The demo car touching something says nothing about the driver either.
                if (!shown) {
                    if (firstTry()) state = state.copy(assist = Assist.FULL) else adaptAssist(engine.lot?.config?.id ?: "", false)
                }
                engine.last?.let { say(Texts.verdictSpeech(it)) }
            }
            Phase.IDLE -> if (engine.dropped != null) {
                val why = engine.dropped
                engine.dropped = null
                stopAutopilot()
                speaker.stop()
                state = state.copy(
                    notice = when (why) {
                        Drop.FAR -> "칸에서 멀리 벗어나 연습을 마쳤어요. 다시 하려면 빈 칸에 세우고 시작하세요"
                        else -> "핸들 각도나 속도를 읽지 못해 연습을 멈췄어요. 신호가 들어오면 다시 시작하세요"
                    },
                    screen = if (state.screen == Screen.RESULT) Screen.DRIVE else state.screen,
                )
            } else if (from == Phase.RECORDING) {
                // The first, unguided attempt is over: play it back, then ask about the surroundings.
                val samples = engine.baseline ?: return
                say("첫 주차를 기록했습니다")
                afterReplay = Screen.EDITOR
                // The drive says how the car went in, and with that which side of it the lane is on.
                // Taken for reversed in without asking, a car driven in nose-first was shown its lane
                // beyond the wall in front of it, and its own drive coming through the wall behind.
                entry = Entry.read(samples)
                val place = LotConfig(Scenarios.REAL, "실제 주차장")
                state = state.copy(
                    screen = Screen.REPLAY,
                    replay = samples,
                    replayTitle = "처음 주차한 길",
                    replayT = 0.0,
                    editing = entry?.on(place) ?: place,
                    entryKnown = entry != null,
                    entryRead = entry != null,
                )
            }
            else -> Unit
        }
    }

    /** The sound of a parking sensor: quicker as the nearest virtual obstacle gets closer. */
    private fun sensorBeep(dt: Double, speed: Double) {
        sinceBeep += dt
        val active = engine.phase == Phase.PARKING || engine.phase == Phase.LEAVING
        val gap = engine.clearance
        if (!active || !state.voice || speed < 0.03 || gap > 1.0) return
        val every = if (gap < 0.3) 0.12 else if (gap < 0.6) 0.28 else 0.55
        if (sinceBeep < every) return
        sinceBeep = 0.0
        try {
            val t = tone ?: android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 55).also { tone = it }
            t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (e: RuntimeException) {
            Log.d(TAG, "No beep: ${e.message}")
        }
    }

    /**
     * The attempt just ended was the first, unguided try at this place: the one every later attempt
     * is compared with. A recorded first parking (level 2) already is that, so the attempt after it is not.
     */
    private fun firstTry(): Boolean = engine.history.size == 1 && engine.baseline == null && state.assist == Assist.SOLO

    /** Less help after a good attempt, more after a bad one. Kept per situation. */
    private fun adaptAssist(id: String, good: Boolean) {
        val now = state.assist
        val next = if (good) {
            if (now == Assist.FULL) Assist.PATH else Assist.SOLO
        } else {
            if (now == Assist.SOLO) Assist.PATH else Assist.FULL
        }
        if (next == now) return
        prefs.edit().putString(KEY_ASSIST + id, next.name).apply()
        state = state.copy(
            assist = next,
            stages = state.stages + (id to next),
            notice = if (good) "잘했어요. 다음은 '${Texts.assistName(next)}'로 해 봅니다" else "다음은 '${Texts.assistName(next)}'로 도와 드릴게요",
        )
    }

    private fun loadAssist(id: String): Assist = try {
        Assist.valueOf(prefs.getString(KEY_ASSIST + id, Assist.FULL.name) ?: Assist.FULL.name)
    } catch (e: Exception) {
        Assist.FULL
    }

    fun cycleAssist() {
        val next = Assist.values()[(state.assist.ordinal + 1) % Assist.values().size]
        val id = engine.lot?.config?.id
        if (id != null) prefs.edit().putString(KEY_ASSIST + id, next.name).apply()
        state = state.copy(assist = next, stages = if (id != null) state.stages + (id to next) else state.stages)
    }

    /** Whether a lesson can be started: its turn has come, or the driver has asked for all of them. */
    fun unlocked(id: String): Boolean = state.openAll || id !in Course.order || Course.open(id, state.passed)

    fun toggleOpenAll() {
        val on = !state.openAll
        prefs.edit().putBoolean(KEY_OPEN_ALL, on).apply()
        state = state.copy(openAll = on)
    }

    private fun speak(hint: Hint?) {
        if (hint == null) return
        val key = "${hint.cue}${hint.dir}"
        if (key != spokenCue) {
            spokenCue = key
            spokenMark = hint.remaining
            spokenAt = clock
            say(Texts.speech(hint))
            return
        }
        if (hint.cue == Cue.STEER && kotlin.math.abs(hint.turnDeg) > TURN_MORE && clock - spokenAt > STEER_REPEAT) {
            // In reverse the car's rear camera has the screen, so the wheel cannot be shown round:
            // what is still to turn is said again every few seconds until it is done.
            spokenAt = clock
            say(Texts.steerMore(hint))
        }
        if (hint.cue == Cue.MOVE) {
            // Count down the last stretch.
            for (mark in MARKS) {
                if (spokenMark > mark && hint.remaining <= mark) {
                    say(Texts.spokenMetres(mark))
                    break
                }
            }
            spokenMark = hint.remaining
        }
    }

    // ------------------------------------------------------------------ what the driver chooses

    fun pickLevel(level: Level) {
        engine.reset()
        stopAutopilot()
        state = if (level == Level.OPEN) {
            state.copy(level = level, screen = Screen.SCENARIOS, chosen = null, notice = null)
        } else {
            // A real place keeps its own help level, like each lesson does.
            state.copy(level = level, screen = Screen.DRIVE, chosen = null, notice = null, assist = loadAssist(Scenarios.REAL))
        }
    }

    fun pickScenario(config: LotConfig) {
        if (!unlocked(config.id)) return
        engine.reset()
        state = state.copy(chosen = config, screen = Screen.DRIVE, notice = null, assist = loadAssist(config.id))
    }

    fun editCustom() {
        entry = null
        state = state.copy(editing = state.custom, screen = Screen.EDITOR, entryKnown = true, entryRead = false)
    }

    /**
     * Real lots: skip the recorded first attempt and describe the place the car is parked in now.
     * With no drive to read, how the car went in has to be asked before anything is drawn.
     */
    fun describeHere() {
        entry = null
        state = state.copy(editing = LotConfig(Scenarios.REAL, "실제 주차장"), screen = Screen.EDITOR, entryKnown = false, entryRead = false)
    }

    /** What the first parking recorded at this place says about how the car went in, when there is one. */
    var entry: Entry? = null
        private set

    fun updateEditing(config: LotConfig) {
        state = state.copy(editing = config)
    }

    /** The driver has said how the car went in. From here on it is their word, not the recording's. */
    fun entryAnswered() {
        state = state.copy(entryKnown = true, entryRead = false)
    }

    fun saveEditing() {
        val config = state.editing ?: return
        // A real place is not laid out until it is known which side of the car the lane is on.
        if (state.level != Level.OPEN && !state.entryKnown) return
        if (state.level == Level.OPEN) {
            prefs.edit().putString(KEY_CUSTOM, config.encode()).apply()
            engine.reset()
            state = state.copy(custom = config, chosen = config, editing = null, screen = Screen.DRIVE)
        } else if (engine.blind) {
            state = state.copy(notice = "핸들 각도나 속도를 아직 읽지 못해 자리를 정할 수 없어요")
        } else {
            engine.setTarget(config)
            // After the recorded first parking comes guidance (README, level 2), at the level kept for this place.
            state = state.copy(editing = null, screen = Screen.DRIVE, notice = "이 자리를 기억했어요", assist = loadAssist(Scenarios.REAL))
        }
    }

    /** Level 1: the car's present position becomes the space to park in. */
    fun setTargetHere() {
        val config = state.chosen ?: return
        if (engine.gear != Gear.PARK || engine.blind) return
        engine.setTarget(config)
        // The first try is unguided on purpose: it shows where the driver stands, and its path is
        // kept as the grey line every later attempt is compared with.
        state = state.copy(assist = Assist.SOLO, notice = "첫 시도는 안내 없이. 그 길을 기억해 둡니다")
        say("이 자리가 목표 칸입니다. 먼저 안내 없이 나갔다가 다시 주차해 보세요.")
    }

    fun startRecording() {
        if (engine.blind) return
        engine.startRecording()
        say("기록을 시작합니다. 평소대로 주차하고 기어를 피에 두세요.")
    }

    fun again() {
        engine.again()
        state = state.copy(screen = Screen.DRIVE)
    }

    /** Level 1: the same spot, a different situation around it, picked at random. */
    fun randomLot() {
        val now = engine.lot?.config?.id
        val next = (Scenarios.all.filter { it.id != now && unlocked(it.id) }.ifEmpty { Scenarios.all }).random()
        engine.retarget(next)
        state = state.copy(chosen = next, screen = Screen.DRIVE, assist = loadAssist(next.id), notice = "새 환경: ${next.name}")
        say("새 환경입니다. ${next.name}")
    }

    /** Level 1: the same spot, with the next lesson's situation around it. */
    fun nextLesson(next: LotConfig) {
        if (!unlocked(next.id)) return
        engine.retarget(next)
        state = state.copy(chosen = next, screen = Screen.DRIVE, assist = loadAssist(next.id), notice = "다음 단계: ${next.name}")
        say("다음 단계입니다. ${next.name}")
    }

    /** Real lots: "I am parked properly" puts the model back on the mark and clears any drift. */
    fun reanchor() {
        engine.reanchor()
        state = state.copy(screen = Screen.DRIVE, notice = "위치를 다시 맞췄어요")
    }

    fun showReplay(samples: List<Sample>, title: String) {
        afterReplay = state.screen
        state = state.copy(screen = Screen.REPLAY, replay = samples, replayTitle = title, replayT = 0.0)
    }

    fun closeReplay() {
        state = state.copy(screen = afterReplay, replay = null)
    }

    fun open(screen: Screen) {
        state = state.copy(screen = screen)
    }

    fun back() {
        when (state.screen) {
            Screen.REPLAY -> closeReplay()
            Screen.EDITOR -> state = state.copy(screen = if (state.level == Level.OPEN) Screen.SCENARIOS else Screen.DRIVE)
            Screen.DRIVE -> {
                // Leaving the practice ends it. Kept running, it would go on talking, beeping and
                // scoring behind the driver's back, and bring the result screen up on the way home.
                stopAutopilot()
                engine.reset()
                speaker.stop()
                state = state.copy(screen = if (state.level == Level.OPEN) Screen.SCENARIOS else Screen.HOME)
            }
            // The result's own way back: a practice screen left on a finished attempt has nothing to press.
            Screen.RESULT -> again()
            // Both are reached from the settings, so that is where they go back to.
            Screen.PLACES, Screen.SIGNALS -> state = state.copy(screen = Screen.SETTINGS)
            else -> state = state.copy(screen = Screen.HOME)
        }
    }

    fun setVoice(on: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
        if (!on) speaker.stop()
        state = state.copy(voice = on)
    }

    /** Day or night colours for the whole app. */
    fun setLight(on: Boolean) {
        prefs.edit().putBoolean(KEY_LIGHT, on).apply()
        Palette.light = on
    }

    /** Takes effect the next time the app starts. */
    fun setForceDemo(on: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_DEMO, on).apply()
    }

    val forceDemo: Boolean get() = prefs.getBoolean(KEY_FORCE_DEMO, false)

    // ------------------------------------------------------------------ the car's size

    fun setSpec(spec: VehicleSpec) {
        specPinned = true
        val text = encodeSpec(spec)
        val edit = prefs.edit().putString(KEY_SPEC, text)
        link?.vin?.let { edit.putString(KEY_SPEC + it, text) }
        edit.apply()
        state = state.copy(spec = spec, specFromCar = false)
        fitSpec(spec)
        demoLink?.start(spec) { }
    }

    /** Goes back to what the car says about itself. */
    fun useCarSpec() {
        specPinned = false
        val edit = prefs.edit().remove(KEY_SPEC)
        // setSpec keeps the choice for this car too, and that copy wins over what the car reports.
        link?.vin?.let { edit.remove(KEY_SPEC + it) }
        edit.apply()
        state = state.copy(specFromCar = false)
        link?.let { adoptCarSpec(it) }
    }

    private fun encodeSpec(s: VehicleSpec): String =
        listOf(s.id, s.name, s.length, s.width, s.wheelbase, s.rearOverhang, s.steeringRatio, s.lockDeg).joinToString("|")

    private fun decodeSpec(text: String?): VehicleSpec? = try {
        val p = text!!.split("|")
        VehicleSpec(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toDouble(), p[7].toDouble())
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ places to practise at

    /** Asks the driver to allow the app to know where the car is. Set by the activity while it is alive. */
    var askForLocation: (() -> Unit)? = null

    /** Shows the system's question about the car's signals. Set by the activity, the only thing that can ask. */
    var askForCar: (() -> Unit)? = null

    /** The car's speed has not been granted yet: say why it is needed before the system asks. */
    fun explainCarAsk() {
        state = state.copy(carAsk = true)
    }

    /** The driver has read the note. [ask] is false when they would rather carry on without the signal. */
    fun answerCarAsk(ask: Boolean) {
        state = state.copy(carAsk = false)
        val question = askForCar
        if (ask && question != null) question() else reconnect()
    }

    fun openPlaces() {
        state = state.copy(screen = Screen.PLACES, notice = null)
    }

    /** Keeps where the car is now as a place to come back to. */
    fun savePlace() {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askForLocation?.invoke()
            state = state.copy(notice = "위치 사용을 허용한 뒤 다시 눌러 주세요")
            return
        }
        val here = lastLocation()
        if (here == null) {
            state = state.copy(notice = "지금 위치를 아직 받지 못했어요. 잠시 뒤 다시 눌러 주세요")
            return
        }
        var number = state.places.size + 1
        while (state.places.any { it.name == "연습 장소 $number" }) number++
        keepPlaces(state.places + Place("연습 장소 $number", here.latitude, here.longitude), "지금 있는 곳을 저장했어요")
    }

    fun removePlace(place: Place) = keepPlaces(state.places - place, null)

    private fun keepPlaces(places: List<Place>, notice: String?) {
        prefs.edit().putString(KEY_PLACES, places.joinToString("\n") { it.encode() }).apply()
        state = state.copy(places = places, notice = notice)
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? = try {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (e: Exception) {
        Log.d(TAG, "No location: ${e.message}")
        null
    }

    /** Hands a saved place to whatever navigation the car has, as somewhere to go. */
    fun navigateTo(place: Place) {
        val uri = Uri.parse("geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}(${Uri.encode(place.name)})")
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.d(TAG, "No navigation took the place: ${e.message}")
            state = state.copy(notice = "내비게이션을 열지 못했어요")
        }
    }

    // ------------------------------------------------------------------ where and how to practise

    /** Level 1 in an open space with no lines (true) or in an empty space of a car park (false). */
    fun setOpenSpace(on: Boolean) {
        prefs.edit().putBoolean(KEY_OPEN_SPACE, on).apply()
        engine.bounded = !on
        state = state.copy(openSpace = on)
    }

    /** Real cones at the front corners of the space: kept clear of, and measured against. */
    fun setCones(on: Boolean) {
        prefs.edit().putBoolean(KEY_CONES, on).apply()
        engine.cones = on
        // The place the car is in now is laid out again, with or without them.
        if (engine.phase == Phase.READY) engine.lot?.config?.let { engine.retarget(it) }
        state = state.copy(cones = on)
    }

    fun setEyesOut(on: Boolean) {
        prefs.edit().putBoolean(KEY_EYES_OUT, on).apply()
        state = state.copy(eyesOut = on)
    }

    // ------------------------------------------------------------------ the small window in reverse

    /** Opens the system's page for letting the app draw over others. False when this device has none. */
    var askForOverlay: (() -> Boolean)? = null

    /**
     * Whether a small window should stay up over the rear camera. It needs the driver's leave to
     * draw over other apps; asked for here, the one place the driver says they want it.
     */
    fun setFloating(on: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING, on).apply()
        state = state.copy(floating = on, floatAllowed = floatWindow.allowed)
        if (on && !floatWindow.allowed) askForOverlay?.invoke()
    }

    /** Where on the screen the app is, in pixels. Set by the activity while it is alive. */
    var locate: (() -> android.graphics.Rect)? = null

    /** Shows the small window over the app itself for a few seconds, to see that this device draws it at all. */
    fun tryFloating() {
        appArea = locate?.invoke() ?: return
        floatClosed = false
        floatWindow.hide()
        trialUntil = clock + TRIAL
    }

    /** The app is on screen again: the small window has nothing left to do. */
    fun shown() {
        Log.d(TAG, "App on screen")
        foreground = true
        floatClosed = false
        if (clock >= trialUntil) floatWindow.hide()
        val allowed = floatWindow.allowed
        if (allowed != state.floatAllowed) state = state.copy(floatAllowed = allowed)
    }

    /** Something else has the screen. [area] is the part of it the app had, in pixels. */
    fun hidden(area: android.graphics.Rect) {
        Log.d(TAG, "App off screen: area=$area gear=${state.gear} screen=${state.screen}")
        foreground = false
        appArea = area
    }

    fun moveFloating() = floatWindow.move()

    /** The next size up for the small window, and after the largest the smallest again. Remembered. */
    fun resizeFloating() {
        prefs.edit().putInt(KEY_FLOAT_SIZE, floatWindow.resize(floatWindow.size + 1)).apply()
    }

    /** Put away by the driver: it stays away until the app has been on screen again. */
    fun closeFloating() {
        floatClosed = true
        floatWindow.hide()
    }

    /**
     * The small window goes up when a practice is on, the car is in reverse and something else, the
     * rear camera, has the screen. It then stays until the app is back, so a car that keeps its
     * camera up for a moment after reverse does not make it flicker. Never with the demo car: no
     * camera takes the screen from that.
     */
    private fun float(gear: Gear) {
        // A practice that ends while the camera is up, parked or bumped, is said in the window too.
        val practising = (state.screen == Screen.DRIVE || (state.screen == Screen.RESULT && floatWindow.showing)) && demoLink == null
        val trial = clock < trialUntil
        val want = !floatClosed && (trial || (!foreground && practising && state.floating &&
            (gear == Gear.REVERSE || (floatWindow.showing && gear != Gear.PARK))))
        if (want && !floatWindow.showing) {
            // Not allowed, or refused by the system: try again in a moment, not every fiftieth of a second.
            if (clock >= floatRetryAt && !floatWindow.show(appArea)) floatRetryAt = clock + FLOAT_RETRY
        } else if (!want && floatWindow.showing) {
            Log.i(TAG, "Small window taken down: foreground=$foreground screen=${state.screen} gear=$gear closed=$floatClosed")
            floatWindow.hide()
        }
    }

    /** Which way the car counts a turn of the wheel. It differs from car to car, so it can be switched. */
    fun setRightNegative(on: Boolean) {
        prefs.edit().putBoolean(KEY_RIGHT_NEGATIVE, on).apply()
        state = state.copy(rightNegative = on)
        if (demoLink == null) reconnect()
    }

    private fun steeringUnit(): SteeringUnit = SteeringUnit.values().getOrElse(prefs.getInt(KEY_STEERING_UNIT, 0)) { SteeringUnit.AUTO }

    /** Whether the car's steering number is the front wheels' angle or the steering wheel's. */
    fun setSteeringUnit(unit: SteeringUnit) {
        prefs.edit().putInt(KEY_STEERING_UNIT, unit.ordinal).apply()
        state = state.copy(steeringUnit = unit)
        if (demoLink == null) reconnect()
    }

    /** Leaves the demo car and tries the real one again. */
    fun retryCar() {
        prefs.edit().putBoolean(KEY_FORCE_DEMO, false).apply()
        state = state.copy(problem = null)
        reconnect()
    }

    /** Every signal and permission as it stands now, for the page that shows them. */
    fun signals(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        rows.addAll(link?.describe() ?: listOf("차량" to "연결 전 (권한을 묻는 중)"))
        for (permission in CAR_PERMISSIONS) {
            val granted = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            // A permission this device does not define cannot be given, and is not something gone wrong.
            val known = try {
                app.packageManager.getPermissionInfo(permission, 0)
                true
            } catch (e: Exception) {
                false
            }
            val name = "권한 " + permission.substringAfterLast("permission.") + (if (permission.startsWith("pleos")) " (Pleos)" else "")
            rows.add(name to if (granted) "허용" else if (known) "없음" else "이 기기에는 이 권한이 정의되어 있지 않음")
        }
        rows.add("후진 중 작은 안내 창 (다른 앱 위에 표시)" to if (floatWindow.allowed) "허용" else "허용되지 않음")
        rows.add("작은 화면(PIP) 지원" to if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) "예" else "아니오")
        val p = engine.pose
        rows.add("앱이 계산한 위치" to String.format(java.util.Locale.US, "x %.2f m · y %.2f m · %.0f°", p.x, p.y, Math.toDegrees(p.h)))
        rows.add("단계" to engine.phase.name)
        rows.add("차가 지나가 본 통로 깊이" to String.format(java.util.Locale.US, "%.2f m", engine.proven))
        return rows
    }

    /** The driver has gone through the check that comes before the first practice. */
    fun confirmSafety() {
        state = state.copy(checked = true)
        say("좋습니다. 움직이기 전에는 항상 거울과 뒤를 직접 확인하세요.")
    }

    // ------------------------------------------------------------------ the demo car

    /** The demo car's door, to try what happens when a real one opens. */
    fun demoDoor(open: Boolean) {
        demoLink?.doorOpen = open
    }

    fun demoGear(gear: Gear) {
        val sim = demoLink?.sim ?: return
        if (sim.speed < 0.3) sim.gear = gear
    }

    fun demoSpeed(metresPerSecond: Double) {
        demoLink?.sim?.targetSpeed = metresPerSecond
    }

    fun demoWheel(degrees: Double) {
        demoLink?.sim?.targetWheel = degrees
    }

    val demoTargetSpeed: Double get() = demoLink?.sim?.targetSpeed ?: 0.0
    val demoTargetWheel: Double get() = demoLink?.sim?.targetWheel ?: 0.0

    /** Lets the demo car show how it is done: out of the space along the planned path, then back in by the guide. */
    fun toggleAutopilot() {
        if (demoLink == null) return
        if (state.autopilot) {
            stopAutopilot()
            return
        }
        script = null
        state = state.copy(autopilot = true)
    }

    private fun stopAutopilot() {
        script = null
        demoLink?.sim?.targetSpeed = 0.0
        if (state.autopilot) state = state.copy(autopilot = false)
    }

    private fun autopilot(demo: DemoCarLink) {
        val sim = demo.sim
        val signed = if (sim.gear == Gear.REVERSE) -sim.speed else sim.speed
        when (engine.phase) {
            Phase.READY, Phase.LEAVING -> {
                var s = script
                if (s == null && engine.phase == Phase.READY) {
                    // The way out is planned from the mark, so the demo car starts from it. It is not
                    // a real car: putting it back on the mark moves nothing but the picture, a few
                    // centimetres. Starting from where the last run stopped, it drove into the next car.
                    engine.reanchor()
                    val lot = engine.lot ?: return
                    // Leave by a different way each time, so the way back is never just the way out reversed.
                    val planner = Planner(state.spec, lot)
                    val leftFirst = kotlin.random.Random.nextBoolean()
                    val exit = planner.wander(leftFirst) ?: planner.wander(!leftFirst)
                    if (exit == null) {
                        stopAutopilot()
                        state = state.copy(notice = "이 환경은 한 번에 나갈 길이 없어 시범이 없습니다")
                        return
                    }
                    s = Script(state.spec, exit)
                    script = s
                }
                if (s != null) sim.follow(s.update(lastDs, signed, sim.gear, sim.wheelDeg))
            }
            Phase.PARKING -> {
                val s = script
                if (s != null && !s.finished) sim.follow(s.update(lastDs, signed, sim.gear, sim.wheelDeg)) else sim.follow(engine.hint)
            }
            // onPhase ends the demo, and has to know that it was one.
            Phase.FINISHED, Phase.CRASHED -> Unit
            else -> stopAutopilot()
        }
    }

    companion object {
        /**
         * What the app reads from the car. Declaring these is not enough: the driver is asked for
         * them when the app first opens, and the car is connected once they have answered.
         */
        /** The one car permission the driver is asked about while the app runs. */
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

        val CAR_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_POWERTRAIN",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.READ_CAR_STEERING",
            "pleos.car.permission.CAR_INFO",
            "pleos.car.permission.CAR_SPEED",
            "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_POWERTRAIN",
            "pleos.car.permission.TTS_SERVICE",
        )

        const val TAG = "ParkCoach"

        /** Milliseconds between two readings of the car when no frame asks for one sooner. */
        const val PULSE_MS = 20L

        /** Seconds: the longest step the trainer takes at once, and the longest gap it will make up for. */
        const val STEP = 0.02
        const val MAX_GAP = 0.5

        /** Seconds between two reminders of how much of the wheel is left to turn. */
        const val STEER_REPEAT = 4.0

        /** Seconds the small window stays up when it is tried from the signal page. */
        const val TRIAL = 12.0

        /** Seconds before the small window is tried again after the system would not show it. */
        const val FLOAT_RETRY = 1.0
        const val REPLAY_SPEED = 4.0
        private val MARKS = listOf(2.0, 1.0, 0.5)
        private const val KEY_SPEC = "spec"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_BEST = "best_"
        private const val KEY_ASSIST = "assist_"
        private const val KEY_OPEN_SPACE = "openSpace"
        private const val KEY_CONES = "cones"
        private const val KEY_EYES_OUT = "eyesOut"
        private const val KEY_FLOATING = "floating"
        private const val KEY_FLOAT_SIZE = "floatSize"
        private const val KEY_PASS = "pass_"
        private const val KEY_OPEN_ALL = "openAll"
        private const val KEY_PLACES = "places"
        private const val KEY_VOICE = "voice"
        private const val KEY_LIGHT = "light"
        private const val KEY_FORCE_DEMO = "forceDemo"
        private const val KEY_RIGHT_NEGATIVE = "rightTurnNegative"
        private const val KEY_STEERING_UNIT = "steeringUnit"
    }
}
