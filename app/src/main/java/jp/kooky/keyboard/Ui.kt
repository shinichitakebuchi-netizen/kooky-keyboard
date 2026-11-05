package jp.kooky.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

// 配色：ネイビー × オレンジ
object Ui {
    val BG: Int = Color.parseColor("#14213D")
    val CARD: Int = Color.parseColor("#1F2F52")
    val KEY: Int = Color.parseColor("#2A3B63")
    val ORANGE: Int = Color.parseColor("#F28C28")
    val TEXT: Int = Color.WHITE
    val SUB: Int = Color.parseColor("#9AA7C2")

    fun round(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    // 押したときに波紋が出る角丸背景
    fun pressable(color: Int, radius: Float, ripple: Int = 0x33FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), round(color, radius), null)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// キーボードの配色（onAccent＝オレンジの上の文字色）
class Palette(
    val name: String,
    val label: String,
    val bg: Int,
    val card: Int,
    val key: Int,
    val accent: Int,
    val text: Int,
    val sub: Int,
    val onAccent: Int,
    // 和の見た目（角をほぼ四角に・細い罫線・明朝の文字）
    val wa: Boolean = false,
    val line: Int = 0
)

object Themes {
    // 墨（標準）：縁・庵とそろえた 墨・生成り・朱
    val SUMI = Palette(
        "sumi", "墨",
        Color.parseColor("#1E2024"), Color.parseColor("#25272C"), Color.parseColor("#2C2E34"),
        Color.parseColor("#E0632F"), Color.parseColor("#ECE7DC"), Color.parseColor("#8C8F96"), Color.parseColor("#F3EEE3"),
        wa = true, line = Color.parseColor("#3A3D44")
    )
    // 生成り：和紙の地に墨の文字と朱
    val KINARI = Palette(
        "kinari", "生成り",
        Color.parseColor("#E8E2D5"), Color.parseColor("#DDD6C7"), Color.parseColor("#F4F0E7"),
        Color.parseColor("#D2542A"), Color.parseColor("#2A2A2A"), Color.parseColor("#7A766E"), Color.parseColor("#F4F0E7"),
        wa = true, line = Color.parseColor("#CBC3B2")
    )
    val NAVY = Palette(
        "navy", "ネイビー",
        Color.parseColor("#14213D"), Color.parseColor("#1F2F52"), Color.parseColor("#2A3B63"),
        Color.parseColor("#F28C28"), Color.WHITE, Color.parseColor("#9AA7C2"), Color.parseColor("#14213D")
    )
    val BLACK = Palette(
        "black", "ブラック",
        Color.parseColor("#0E0E10"), Color.parseColor("#1C1C1F"), Color.parseColor("#2B2B30"),
        Color.parseColor("#F28C28"), Color.WHITE, Color.parseColor("#9A9AA2"), Color.parseColor("#111111")
    )
    val LIGHT = Palette(
        "light", "ライト",
        Color.parseColor("#F2F0EB"), Color.parseColor("#E2DFD8"), Color.WHITE,
        Color.parseColor("#F28C28"), Color.parseColor("#1E1E1E"), Color.parseColor("#6B6B6B"), Color.parseColor("#1E1E1E")
    )
    val ALL = listOf(SUMI, KINARI, NAVY, BLACK, LIGHT)

    fun of(name: String?): Palette = ALL.firstOrNull { it.name == name } ?: SUMI
}
