package com.parkcoach.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.parkcoach.core.Gear
import com.parkcoach.core.VehicleSpec
import com.parkcoach.core.Vehicles
import kotlin.math.abs

/**
 * The standard Android Automotive car service. Pleos points apps here for vehicle speed
 * (PERF_VEHICLE_SPEED) and the selected gear (GEAR_SELECTION), and the same service can describe
 * the car's body (INFO_EXTERIOR_DIMENSIONS), which lets the app set itself up without asking.
 *
 * Kept in its own class so that a device without the car library fails here and nowhere else.
 */
class AaosCar(context: Context) {
    @Volatile
    var speed: Double = 0.0
        private set

    @Volatile
    var speedSeen: Boolean = false
        private set

    @Volatile
    var gear: Gear = Gear.UNKNOWN
        private set

    @Volatile
    var gearSeen: Boolean = false
        private set

    var detected: VehicleSpec? = null
        private set

    /** What could not be read, by signal, for the signal page. */
    val trouble = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val car: Car? = Car.createCar(context.applicationContext)
    private val manager: CarPropertyManager? = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // A value the car marks unavailable is no value at all, and the last one it gave is no
            // longer true: kept, a speed that went away would go on adding distance as if still rolling.
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                lost(value.propertyId, "차가 값을 주지 않음")
                return
            }
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val v = value.value as? Float ?: return
                    // The car reports reverse as negative; the gear already tells the app the direction.
                    speed = abs(v).toDouble()
                    speedSeen = true
                    trouble.remove("속도")
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    // android.car.VehicleGear: neutral 1, reverse 2, park 4, drive 8, and 16 upwards for numbered gears.
                    gear = when (value.value as? Int ?: return) {
                        4 -> Gear.PARK
                        2 -> Gear.REVERSE
                        1 -> Gear.NEUTRAL
                        0 -> Gear.UNKNOWN
                        else -> Gear.DRIVE
                    }
                    gearSeen = gear != Gear.UNKNOWN
                    if (gearSeen) trouble.remove("기어")
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "Car property $propertyId reported an error")
            lost(propertyId, "차가 오류를 알림")
        }
    }

    /** A signal that stopped: unknown again until the car gives a value, so the trainer waits for it. */
    private fun lost(propertyId: Int, why: String) {
        when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                speedSeen = false
                speed = 0.0
                trouble["속도"] = why
            }
            VehiclePropertyIds.GEAR_SELECTION -> {
                gearSeen = false
                gear = Gear.UNKNOWN
                trouble["기어"] = why
            }
        }
    }

    fun start() {
        val m = manager
        if (m == null) {
            trouble["Android Automotive"] = "차량 서비스 없음"
            return
        }
        listen(m, "속도", VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
        listen(m, "기어", VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        try {
            // Millimetres: height, length, width, width with mirrors, wheelbase, front track, rear track, turning radius.
            val d = m.getIntArrayProperty(VehiclePropertyIds.INFO_EXTERIOR_DIMENSIONS, 0)
            val model = try {
                m.getProperty(String::class.java, VehiclePropertyIds.INFO_MODEL, 0)?.value ?: ""
            } catch (e: Exception) {
                ""
            }
            if (d != null && d.size >= 5) {
                detected = Vehicles.fromDimensions(model, d[1], d[2], d[4], if (d.size >= 8) d[7] else 0)
            }
        } catch (e: Exception) {
            Log.d(TAG, "The car does not report its dimensions: ${e.message}")
        }
    }

    private fun listen(m: CarPropertyManager, what: String, property: Int, rate: Float) {
        try {
            if (!m.registerCallback(callback, property, rate)) trouble[what] = "차가 이 신호를 주지 않음"
        } catch (e: SecurityException) {
            trouble[what] = "권한 없음"
        } catch (e: Exception) {
            trouble[what] = e.message ?: e.javaClass.simpleName
        }
    }

    fun stop() {
        try {
            manager?.unregisterCallback(callback)
            car?.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "Car service already gone: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ParkCoach"
    }
}
