package jp.kooky.keyboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

// スマホの左右の傾きを読む（加速度センサー。許可はいらない）
// roll：右側が下がっているとプラス、左側が下がっているとマイナス（度）。少しなめらかにしてある
class HandSensor(context: Context, private val onRoll: (Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acc: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false
    private var smooth = Float.NaN

    val available: Boolean get() = acc != null

    fun start() {
        if (running || acc == null) return
        running = true
        smooth = Float.NaN
        // ゆっくりした間隔（約0.2秒ごと）で読む。電池への影響を小さく
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        // 右側が下がると x はマイナスになる
        val roll = Math.toDegrees(atan2(-x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
        smooth = if (smooth.isNaN()) roll else smooth * 0.7f + roll * 0.3f
        onRoll(smooth)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
