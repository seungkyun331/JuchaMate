package com.parkcoach.vehicle

import com.parkcoach.core.Gear
import com.parkcoach.core.SimCar
import com.parkcoach.core.VehicleSpec

/**
 * The three things the trainer needs from a car, read fresh every display frame:
 * how fast it is going, where the steering wheel is, and which gear is selected.
 */
interface CarLink {
    val isDemo: Boolean

    /** Metres per second, never negative. The gear says which way. */
    val speed: Double

    /** Steering wheel angle in degrees, right positive. */
    val wheelDeg: Double
    val gear: Gear

    /** True once the car has reported a gear, so the link is known to be alive. */
    val alive: Boolean

    /** True while the driver's door is open, on cars that say so. Practice waits until it is shut. */
    val doorOpen: Boolean get() = false

    /** False until the car has reported a steering angle: an angle not yet read is not "straight ahead". */
    val steeringKnown: Boolean get() = true

    /** False until the car has reported a speed or a distance: a speed not yet read is not "standing still". */
    val speedKnown: Boolean get() = true

    /**
     * How far the steering wheel turns to one side, in the same degrees as [wheelDeg], when the car
     * says so. A plan must never ask for more lock than this. Null when the car does not say.
     */
    val wheelLimit: Double? get() = null

    /** Everything this link is reading, as a label and what it holds now, for the signal page. */
    fun describe(): List<Pair<String, String>> = emptyList()

    /** The car's own size and turning circle, when it reports them. */
    val detected: VehicleSpec?
    val vin: String?

    fun start(spec: VehicleSpec, onProblem: (String) -> Unit)

    /** The car's figures changed (size, steering ratio): a link that converts with them uses the new ones. */
    fun useSpec(spec: VehicleSpec) = Unit

    fun stop()
}

/** A simulated car for tablets, plain emulators and rehearsals. Its controls are on the screen. */
class DemoCarLink(spec: VehicleSpec) : CarLink {
    var sim: SimCar = SimCar(spec)
        private set

    override val isDemo: Boolean = true
    override val speed: Double get() = sim.speed
    override val wheelDeg: Double get() = sim.wheelDeg
    override val gear: Gear get() = sim.gear
    override val alive: Boolean = true

    /** Set from the demo controls, to try what an open door does. */
    override var doorOpen: Boolean = false
    override val detected: VehicleSpec? = null
    override val vin: String? = null

    override fun start(spec: VehicleSpec, onProblem: (String) -> Unit) {
        sim = SimCar(spec)
    }

    override fun stop() = Unit

    override fun describe(): List<Pair<String, String>> = listOf(
        "차량" to "데모 차량 (화면 아래 조작판)",
        "조향각" to String.format(java.util.Locale.US, "%.0f°", sim.wheelDeg),
        "기어" to sim.gear.name,
        "속도" to String.format(java.util.Locale.US, "%.2f m/s", sim.speed),
        "운전석 문" to if (doorOpen) "열림" else "닫힘",
    )
}
