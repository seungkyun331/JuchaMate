package com.parkcoach.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parkcoach.core.Gear
import com.parkcoach.core.VehicleSpec
import java.util.Locale
import kotlin.math.abs

/** How the car's steering number is to be read. Cars differ, so it can be set by hand. */
enum class SteeringUnit { AUTO, FRONT_WHEELS, STEERING_WHEEL }

/**
 * The real car, and the Pleos Connect emulator.
 *
 *   Steering angle   Pleos Vehicle SDK   getSteeringWheel(): registerSteeringWheelAngle, getCurrentSteeringWheelAngle   CAR_DRIVE, READ_CAR_STEERING
 *   Speed            Android Automotive  PERF_VEHICLE_SPEED                                                              android CAR_SPEED
 *   Gear             Android Automotive  GEAR_SELECTION, and the Vehicle SDK's gear where a car has it                   android CAR_POWERTRAIN
 *   Driver's door    Pleos Vehicle SDK   getDoor(): registerDoorPosition, getCurrentDoorPosition                         CAR_DOORS
 *   Identity         Pleos Vehicle SDK   getCarInfo().getVINInfo, plus Android Automotive INFO_EXTERIOR_DIMENSIONS       CAR_INFO
 *
 * The Vehicle SDK is called by name (see [Sdk]). Its listeners only speak when a value changes,
 * so each signal is also read once at the start: a wheel that has not moved yet still has an angle.
 * SDK callbacks arrive on their own threads; they only write single values that the frame loop reads.
 */
class PleosCarLink(
    private val context: Context,
    /** The Pleos emulator reports a right turn as a negative angle. */
    private val rightTurnIsNegative: Boolean,
    private val unit: SteeringUnit = SteeringUnit.AUTO,
) : CarLink {
    override val isDemo: Boolean = false

    private var vehicle: Any? = null
    private var aaos: AaosCar? = null
    private var spec: VehicleSpec? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    /** What stops each stream of updates, by signal. A signal named here is being listened to. */
    private val stops = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** The last thing that went wrong with each signal, by name, for the signal page. */
    private val trouble = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var angle = 0.0

    /** Unknown is not zero: until an angle has arrived the car is not taken to be steering straight. */
    @Volatile private var angleSeen = false
    @Volatile private var angleRange = 0.0
    @Volatile private var sdkGear = Gear.UNKNOWN
    @Volatile private var door = false
    @Volatile private var doorSeen = false

    @Volatile
    override var vin: String? = null
        private set

    override val doorOpen: Boolean get() = door
    override val steeringKnown: Boolean get() = angleSeen
    override val speedKnown: Boolean get() = aaos?.speedSeen == true
    override val speed: Double get() = aaos?.speed ?: 0.0
    override val detected: VehicleSpec? get() = aaos?.detected

    override val gear: Gear
        get() {
            val a = aaos
            return if (a != null && a.gearSeen) a.gear else sdkGear
        }

    /** True once the car has said anything at all, so the link is known to be alive. */
    override val alive: Boolean get() = aaos?.gearSeen == true || aaos?.speedSeen == true || sdkGear != Gear.UNKNOWN || angleSeen

    /**
     * Some cars report the front wheels' angle (a range of some 40 degrees), others the steering
     * wheel's (500 and more). The range the car declares tells them apart.
     */
    private val frontWheels: Boolean
        get() = when (unit) {
            SteeringUnit.FRONT_WHEELS -> true
            SteeringUnit.STEERING_WHEEL -> false
            SteeringUnit.AUTO -> angleRange in 1.0..60.0
        }

    override val wheelDeg: Double
        get() {
            val signed = if (rightTurnIsNegative) -angle else angle
            return if (frontWheels) signed * (spec?.steeringRatio ?: 15.0) else signed
        }

    /** The range the car declares for its angle, turned into the same units as [wheelDeg]. */
    override val wheelLimit: Double?
        get() {
            val range = angleRange
            if (range <= 0.0) return null
            return if (frontWheels) range * (spec?.steeringRatio ?: 15.0) else range
        }

    /** A car that reports its front wheels is converted with the ratio the engine plans with, not the one it started with. */
    override fun useSpec(spec: VehicleSpec) {
        this.spec = spec
    }

    override fun start(spec: VehicleSpec, onProblem: (String) -> Unit) {
        this.spec = spec
        if (started) return
        started = true
        try {
            val a = AaosCar(context)
            a.start()
            aaos = a
        } catch (e: Throwable) {
            trouble["Android Automotive"] = "차량 서비스 없음"
            Log.w(TAG, "Android Automotive car service is not available", e)
        }
        val type = Sdk.type("ai.pleos.playground.vehicle.Vehicle")
        if (type == null) {
            trouble["Vehicle SDK"] = "이 기기에 없음"
            return
        }
        val v = try {
            type.getConstructor(Context::class.java).newInstance(context.applicationContext)
        } catch (e: Throwable) {
            trouble["Vehicle SDK"] = "시작 실패"
            onProblem("차량 연결에 실패했습니다")
            Log.w(TAG, "Vehicle SDK did not start", e)
            return
        }
        vehicle = v
        if (!Sdk.run(v, "initialize")) {
            trouble["Vehicle SDK"] = "initialize 실패"
            onProblem("차량 연결에 실패했습니다")
            return
        }
        // The SDK reaches the car's service in the background, and an early question gets no answer.
        // So the signals are asked for a moment later, and again for any that is still silent.
        for (wait in WAITS) main.postDelayed({ connect(v) }, TOKEN, wait)
    }

    /** Listens to each signal and reads it once. Safe to repeat: only what is still missing is asked again. */
    private fun connect(v: Any) {
        if (!started) return
        val wheel = Sdk.fetch(v, "getSteeringWheel")
        if (wheel == null) {
            trouble[STEERING] = "getSteeringWheel 없음"
        } else {
            val take = { x: Any? ->
                val d = Sdk.number(x)
                if (d != null) {
                    angle = d
                    angleSeen = true
                    trouble.remove(STEERING)
                }
            }
            watch(STEERING, wheel, "registerSteeringWheelAngle", "unregisterSteeringWheelAngle", null, take)
            if (!angleSeen) Sdk.ask(wheel, "getCurrentSteeringWheelAngle", null, { why -> if (!angleSeen) trouble[STEERING] = why }, take)
            if (angleRange == 0.0) {
                Sdk.ask(wheel, "checkSteeringWheelAngleCapabilities", null, { why -> Log.d(TAG, "No steering range: $why") }) { x ->
                    Sdk.bound(x)?.let { angleRange = it }
                }
            }
        }

        val doors = Sdk.fetch(v, "getDoor")
        if (doors != null) {
            // Anything but fully shut counts as open. The driver sits front left.
            val take = { x: Any? ->
                val pos = Sdk.number(x)
                if (pos != null) {
                    doorSeen = true
                    door = pos > 0
                    trouble.remove(DOOR)
                }
            }
            watch(DOOR, doors, "registerDoorPosition", "unregisterDoorPosition", DRIVER, take)
            if (!doorSeen) Sdk.ask(doors, "getCurrentDoorPosition", DRIVER, { why -> if (!doorSeen) trouble[DOOR] = why }, take)
        }

        // Android Automotive already gives the gear on every car; this is a second source where the SDK has one.
        val gears = Sdk.fetch(v, "getGear")
        if (gears != null) {
            val take = { x: Any? ->
                sdkGear = when (Sdk.name(x)) {
                    "GEAR_PARK" -> Gear.PARK
                    "GEAR_REVERSE" -> Gear.REVERSE
                    "GEAR_NEUTRAL" -> Gear.NEUTRAL
                    "GEAR_UNKNOWN", null -> Gear.UNKNOWN
                    else -> Gear.DRIVE
                }
            }
            watch("기어 (Vehicle SDK)", gears, "registerGearSelection", "unregisterGearSelection", null, take)
            if (sdkGear == Gear.UNKNOWN) {
                Sdk.named(gears, "getCurrentGear").firstOrNull()?.let { Sdk.ask(gears, it, null, { }, take) }
            }
        }

        if (vin == null) {
            Sdk.ask(Sdk.fetch(v, "getCarInfo"), "getVINInfo", null, { why -> Log.d(TAG, "No VIN: $why") }) { x ->
                // The answer may be the number itself or an object that holds it; a VIN is 17 letters and digits.
                val text = (Sdk.property(x, "vin", "vinNumber", "value") ?: x)?.toString() ?: ""
                vin = VIN.find(text)?.value ?: text.takeIf { it.length in 6..24 && it.all { c -> c.isLetterOrDigit() } }
            }
        }
    }

    private fun watch(signal: String, domain: Any, register: String, unregister: String, area: String?, onValue: (Any?) -> Unit) {
        if (stops.containsKey(signal)) return
        val stop = Sdk.watch(domain, register, unregister, area, { why -> trouble[signal] = why }, onValue)
        if (stop != null) stops[signal] = stop
    }

    override fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(TOKEN)
        for (stop in stops.values) {
            try {
                stop()
            } catch (e: Throwable) {
                Log.d(TAG, "Stopping a signal failed: $e")
            }
        }
        stops.clear()
        aaos?.stop()
        Sdk.run(vehicle, "release")
        vehicle = null
    }

    override fun describe(): List<Pair<String, String>> {
        val a = aaos
        val unread = "아직 읽지 못함"
        val rows = ArrayList<Pair<String, String>>()
        rows.add("차량" to if (vehicle != null) "Pleos Vehicle SDK + Android Automotive" else "Android Automotive (Vehicle SDK 없음)")
        rows.add("조향각 (받은 값)" to if (angleSeen) String.format(Locale.US, "%.1f", angle) else unread)
        rows.add("조향각 범위" to if (angleRange > 0.0) String.format(Locale.US, "±%.0f", angleRange) else "차가 알려 주지 않음")
        rows.add(
            "조향각 단위" to when (unit) {
                SteeringUnit.AUTO -> if (frontWheels) "앞바퀴 각도 (범위로 판단)" else if (angleRange > 0.0) "핸들 각도 (범위로 판단)" else "핸들 각도 (범위를 몰라 가정)"
                SteeringUnit.FRONT_WHEELS -> "앞바퀴 각도 (직접 고름)"
                SteeringUnit.STEERING_WHEEL -> "핸들 각도 (직접 고름)"
            },
        )
        rows.add("핸들 방향" to if (rightTurnIsNegative) "오른쪽이 음수" else "오른쪽이 양수")
        rows.add("앱이 쓰는 핸들 각도" to if (angleSeen) String.format(Locale.US, "%.0f° (오른쪽 +)", wheelDeg + 0.0) else unread)
        rows.add(
            "기어" to when {
                a != null && a.gearSeen -> "${a.gear.name} (Android Automotive)"
                sdkGear != Gear.UNKNOWN -> "${sdkGear.name} (Vehicle SDK)"
                else -> unread
            },
        )
        rows.add("속도" to if (a != null && a.speedSeen) String.format(Locale.US, "%.2f m/s · %.1f km/h", a.speed, a.speed * 3.6) else unread)
        rows.add("운전석 문" to if (!doorSeen) "$unread (닫힘으로 봄)" else if (door) "열림" else "닫힘")
        rows.add("차대번호" to (vin?.let { "…" + it.takeLast(4) } ?: unread))
        rows.add("차량 치수" to (a?.detected?.name ?: "차가 알려 주지 않음"))
        rows.add("듣고 있는 신호" to if (stops.isEmpty()) "없음" else stops.keys.sorted().joinToString(", "))
        for ((what, why) in trouble) rows.add("문제: $what" to why)
        if (a != null) for ((what, why) in a.trouble) rows.add("문제: $what (Android Automotive)" to why)
        return rows
    }

    private companion object {
        const val TAG = "ParkCoach"
        const val STEERING = "조향각"
        const val DOOR = "운전석 문"
        const val DRIVER = "ROW_1_LEFT"
        val VIN = Regex("[A-HJ-NPR-Z0-9]{17}")

        /** Marks this link's delayed calls so that stopping it takes them all back. */
        val TOKEN = Any()

        /** Milliseconds after start at which the signals are asked for. */
        val WAITS = longArrayOf(800L, 3000L, 8000L)
    }
}
