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
    fun pressable(color: Int, radius: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(color, radius), null)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
