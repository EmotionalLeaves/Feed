package dev.pfeed.security

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Turn the phone face down, or give it a sharp shake, and the app hides (the same as the
 * three-finger panic touch: locks and goes to the home screen). Listens only while the app is on
 * screen, and only when switched on in Settings.
 */
class FlipToHide(context: Context, private val onHide: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var downSince = 0L
    private var shakes = 0
    private var lastShake = 0L
    private var listening = false

    fun start() {
        if (listening) return
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        downSince = 0L
        shakes = 0
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val now = SystemClock.elapsedRealtime()
        // Face down: gravity pulls through the back of the screen, held for a moment (not a wobble).
        if (z < FACE_DOWN_Z && kotlin.math.abs(x) < FLAT && kotlin.math.abs(y) < FLAT) {
            if (downSince == 0L) downSince = now
            if (now - downSince > FACE_DOWN_MS) { downSince = 0L; onHide() }
        } else downSince = 0L
        // A shake: a few hard jolts close together.
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g > SHAKE_G) {
            if (now - lastShake > SHAKE_GAP_MS) shakes = 0
            if (now - lastShake > SHAKE_DEBOUNCE_MS) { shakes++; lastShake = now }
            if (shakes >= SHAKES) { shakes = 0; onHide() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val FACE_DOWN_Z = -8.6f
        const val FLAT = 2.5f
        const val FACE_DOWN_MS = 350L
        const val SHAKE_G = 2.6f
        const val SHAKES = 3
        const val SHAKE_GAP_MS = 900L
        const val SHAKE_DEBOUNCE_MS = 120L
    }
}
