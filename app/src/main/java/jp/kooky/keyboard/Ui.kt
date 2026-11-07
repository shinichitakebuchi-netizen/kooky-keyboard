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
) {
    // 明るい地の配色か（ナビゲーションバーのアイコンや波紋の色に使う）
    val light: Boolean get() = name == "light" || name == "kinari"

    // 差し色だけ入れ替えたもの。差し色の上の文字は、墨と白練のうち見やすいほう
    fun withAccent(c: Int): Palette {
        val on = if (contrast(c, Iro.SUMI) >= contrast(c, Iro.SHIRONERI)) Iro.SUMI else Iro.SHIRONERI
        return Palette(name, label, bg, card, key, c or (0xFF shl 24), text, sub, on, wa, line)
    }
}

// 2色の見やすさ（WCAGのコントラスト比）
private fun lum(c: Int): Double {
    fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
}
fun contrast(a: Int, b: Int): Double {
    val la = lum(a); val lb = lum(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

object Themes {
    // 墨（標準・ダーク）：庵と同じく、墨の地に鳥の子色の文字。罫線は利休鼠、差し色は紅緋
    // キーは塗らず、利休鼠の細い罫線だけで区切る
    val SUMI = Palette(
        "sumi", "墨",
        Iro.SUMI, Iro.SUMI, Iro.SUMI,
        Iro.BENIHI, Iro.TORINOKO, Iro.KAMENOZOKI_70, Iro.SUMI,
        wa = true, line = Iro.RIKYUNEZUMI
    )
    // 白練（ライト）：庵と同じく、白練の地に藍色の文字。補足・罫線は利休鼠
    val KINARI = Palette(
        "kinari", "白練",
        Iro.SHIRONERI, Iro.SHIRONERI, Iro.SHIRONERI,
        Iro.BENIHI, Iro.AI, Iro.RIKYUNEZUMI, Iro.SUMI,
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

    // 移ろい：日の出から日の入りまで白練、それ以外は墨
    const val UTSUROI = "utsuroi"

    fun of(name: String?): Palette =
        if (name == UTSUROI) (if (ThemeClock.isDay()) KINARI else SUMI)
        else ALL.firstOrNull { it.name == name } ?: SUMI
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
    // キーボードのメイン色（値は colordic.org の「墨」#313131・「白練」#F3F3F2）
    val SUMI = Color.parseColor("#313131")          // 墨：ダークの地・ライトの文字
    val SHIRONERI = Color.parseColor("#F3F3F2")     // 白練：ライトの地・ダークの文字

    // 差し色として選べる色（縁と同じ18色。丁子色・葡萄色は数値未確認のため除外）
    val PRESETS: List<Pair<String, Int>> = listOf(
        "一斤染" to "#F08F90", "鴇色" to "#F3A696", "韓紅花" to "#C91F37",
        "紅緋" to "#F35336", "柿渋色" to "#934337", "鬱金色" to "#E69B3A",
        "玉子色" to "#FFA631", "鳥の子色" to "#E2BE9F", "鶸色" to "#BDA928",
        "萌黄" to "#5B8930", "松葉色" to "#454D32", "若竹色" to "#6B9362",
        "新橋色" to "#006C7F", "甕覗" to "#C6C2B6", "藍色" to "#264348",
        "利休鼠" to "#656255", "紫苑" to "#976E9A", "桔梗色" to "#5D3F6A"
    ).map { it.first to Color.parseColor(it.second) }
}
