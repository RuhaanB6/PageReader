package com.pagereader.android.guidance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Motion-blur guard following Sanketi & Coughlan (ASSETS 2010).
 *
 * The paper high-pass filters raw accelerometer output to strip gravity;
 * TYPE_LINEAR_ACCELERATION does exactly that in sensor fusion, so the norm of
 * its vector is already the quantity the paper thresholds on.
 *
 * Hysteresis comes from the ring buffer: shake latches on if *any* recent sample
 * is over threshold, and only clears once *all* of them are under it. Without
 * that, a norm oscillating around the threshold would flap the callbacks.
 */
class ShakeDetector(
    context: Context,
    private val threshold: Float = 1.2f,
    private val onShake: () -> Unit,
    private val onStable: () -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val normBuffer = ArrayDeque<Float>(RING_BUFFER_SIZE)

    @Volatile
    var isShaking: Boolean = false
        private set

    fun start() {
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        normBuffer.clear()
        isShaking = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val norm = sqrt(x * x + y * y + z * z)

        if (normBuffer.size == RING_BUFFER_SIZE) normBuffer.removeFirst()
        normBuffer.addLast(norm)

        val shakeDetected = normBuffer.any { it > threshold }

        if (shakeDetected && !isShaking) {
            isShaking = true
            mainHandler.post(onShake)
        } else if (!shakeDetected && isShaking) {
            isShaking = false
            mainHandler.post(onStable)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op: accuracy changes don't affect shake detection.
    }

    companion object {
        private const val RING_BUFFER_SIZE = 3
    }
}
