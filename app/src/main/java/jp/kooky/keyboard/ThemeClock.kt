package jp.kooky.keyboard

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// 移ろい：日の出から日の入りまでは白練、それ以外は墨（庵と同じ）
// 綴は位置情報を使わないので、日の出・日の入りは前橋で計算する（日本国内なら数十分以内の差）
object ThemeClock {
    private const val LAT = 36.389   // 前橋
    private const val LON = 139.063

    // 今が昼（日の出〜日の入り）か
    fun isDay(now: Calendar = Calendar.getInstance()): Boolean {
        val (rise, set) = sunTimes(now)
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return m in rise until set
    }

    // その日の 日の出・日の入り（0時からの分）
    fun sunTimes(day: Calendar = Calendar.getInstance()): Pair<Int, Int> = calc(day, LAT, LON) ?: (6 * 60 to 18 * 60)

    fun fmt(m: Int) = "${m / 60}:${"%02d".format(m % 60)}"

    // NOAA の簡易式（誤差は数分）。庵と同じ計算
    private fun calc(day: Calendar, lat: Double, lon: Double): Pair<Int, Int>? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365.0 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (x < -1 || x > 1) return null
        val ha = Math.toDegrees(acos(x))
        val tz = TimeZone.getDefault().getOffset(day.timeInMillis) / 60000.0
        val rise = 720 - 4 * (lon + ha) - eq + tz
        val set = 720 - 4 * (lon - ha) - eq + tz
        return Math.floorMod(rise.toInt(), 1440) to Math.floorMod(set.toInt(), 1440)
    }
}
