package com.mantra.trail

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * THE COMPASS AND THE LEVEL, BOTH OFFLINE AND BOTH FROM THE SAME THREE SENSORS.
 *
 * TRUE NORTH WITHOUT A NETWORK. The magnetometer points at magnetic north, which in Croatia is
 * presently about 5 degrees east of true. Android carries the world magnetic model in the
 * platform, so GeomagneticField gives the declination for a place and a date with nothing
 * fetched — which matters, because the one place a compass is needed is the one place there is no
 * signal. Until there is a fix the app has no place to compute it for, and it says MAG rather
 * than pretending.
 *
 * THE NEEDLE IS FILTERED ROUND THE CIRCLE, never as a plain number: averaging 359 and 1 the
 * ordinary way gives 180 and points the needle backwards (Level.smoothAngle, and Test 1 for it).
 */
class Sensors(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravity: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Magnetic heading, smoothed, degrees. */
    var headingMagnetic: Double = 0.0
        private set

    /** The declination last computed from a fix, or null while there has been no fix. */
    var declination: Double? = null
        private set

    private var gx = 0.0
    private var gy = 0.0
    private var gz = Level.G

    var calibration: Level.Calibration = Level.Calibration()

    /** The level's reading, already smoothed. */
    var level: Level.Reading = Level.read(0.0, 0.0, Level.G)
        private set

    /** True heading when a fix has given the declination, magnetic until then. */
    fun heading(): Double =
        declination?.let { Geo.normaliseDeg(headingMagnetic + it) } ?: headingMagnetic

    val hasCompass: Boolean get() = rotation != null
    val hasLevel: Boolean get() = gravity != null || accelerometer != null

    fun start() {
        rotation?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Gravity is the accelerometer with the motion already taken out of it by the platform.
        // Where it is missing the raw accelerometer is used and the filter does that work instead.
        val tilt = gravity ?: accelerometer
        tilt?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    /** Called when a fix arrives, so the declination is for where the person actually is. */
    fun updateDeclination(fix: Fix) {
        val field = GeomagneticField(
            fix.lat.toFloat(),
            fix.lon.toFloat(),
            (fix.ele ?: 0.0).toFloat(),
            fix.timeMs,
        )
        declination = field.declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val raw = Geo.normaliseDeg(Geo.deg(orientation[0].toDouble()))
                headingMagnetic = Level.smoothAngle(headingMagnetic, raw, 0.2)
            }

            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                gx = Level.smooth(gx, event.values[0].toDouble(), 0.12)
                gy = Level.smooth(gy, event.values[1].toDouble(), 0.12)
                gz = Level.smooth(gz, event.values[2].toDouble(), 0.12)
                level = Level.read(gx, gy, gz, calibration)
            }

            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** The reading as it is now, for zeroing the level on the surface under the phone. */
    fun rawReading(): Level.Reading = Level.read(gx, gy, gz)
}
