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
    // 藍（標準）：縁と同じ 日本の伝統色（藍色・鳥の子色・利休鼠・紅緋）
    // キーは塗らず、利休鼠の細い罫線だけで区切る
    val SUMI = Palette(
        "sumi", "藍",
        Iro.AI, Iro.AI, Iro.AI,
        Iro.BENIHI, Iro.TORINOKO, Iro.KAMENOZOKI_70, Iro.AI,
        wa = true, line = Iro.RIKYUNEZUMI
    )
    // 鳥の子：鳥の子色の地に藍色の文字と紅緋
    val KINARI = Palette(
        "kinari", "鳥の子",
        Iro.TORINOKO, Iro.TORINOKO, Iro.TORINOKO,
        Iro.BENIHI, Iro.AI, Iro.RIKYUNEZUMI, Iro.TORINOKO,
        wa = true, line = Iro.RIKYUNEZUMI
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

// 日本の伝統色（色名は「日本の内装材料辞典 › 日本の色 › 伝統色」、値は Wikipedia「Traditional colors of Japan」、鴇色のみ irocore.com）
// ここにない色は足さない
object Iro {
    val AI = Color.parseColor("#264348")            // 藍色：背景
    val TORINOKO = Color.parseColor("#E2BE9F")      // 鳥の子色：文字（主）
    val KAMENOZOKI = Color.parseColor("#C6C2B6")    // 甕覗
    val KAMENOZOKI_70 = Color.parseColor("#B3C6C2B6") // 甕覗 不透明度70%：文字（補足）
    val RIKYUNEZUMI = Color.parseColor("#656255")   // 利休鼠：罫線・枠線
    val BENIHI = Color.parseColor("#F35336")        // 紅緋：見出し・強調
    val WAKATAKE = Color.parseColor("#6B9362")      // 若竹色：稼働中などの状態
}
