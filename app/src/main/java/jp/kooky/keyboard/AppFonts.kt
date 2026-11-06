package jp.kooky.keyboard

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

// 文字のフォント（縁と同じ3種類だけ）
//  0 = 明朝（標準）… 端末の標準の明朝体（Typeface.SERIF）。初期値
//  1 = Kaisei Tokumin（res/font/kaisei_tokumin.ttf）
//  2 = Yuji Syuku（res/font/yuji_syuku.ttf）
//  3 = システムに合わせる … 端末の設定で選んでいるフォント（Typeface.DEFAULT）
// 1と2は SIL Open Font License 1.1。ライセンス全文は assets/licenses/ に同梱
// ※綴は Compose を使っていない（Viewで作っている）ので、縁の family() に当たるものはなく、Typeface だけを用意する
object AppFonts {
    val names = listOf("明朝（標準）", "Kaisei Tokumin", "Yuji Syuku", "システムに合わせる")

    // 設定の保存先（キーボードと同じファイル）
    const val PREF_KEY = "font"

    // 一度読んだフォントは取っておく（描画のたびに読み直さない）
    private val cache = HashMap<Int, Typeface>()

    fun typeface(ctx: Context, i: Int): Typeface = cache.getOrPut(i) {
        val tf = try {
            when (i) {
                1 -> ctx.resources.getFont(R.font.kaisei_tokumin)
                2 -> ctx.resources.getFont(R.font.yuji_syuku)
                3 -> Typeface.DEFAULT
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        tf ?: Typeface.SERIF
    }

    // 今選んでいるフォントの番号
    fun current(ctx: Context): Int =
        ctx.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getInt(PREF_KEY, 0).coerceIn(0, names.size - 1)

    // 太字にしたいとき。明朝（標準）とシステムは端末の太字を使い、同梱の2つは太字の形を持っていないのでそのまま
    fun bold(ctx: Context, i: Int): Typeface = when (i) {
        0 -> Typeface.create(typeface(ctx, 0), Typeface.BOLD)
        3 -> Typeface.DEFAULT_BOLD
        else -> typeface(ctx, i)
    }

    // 画面の中の文字にまとめて当てる（見本など、自分のフォントを持つ文字は tag で除外）
    const val KEEP = "keep_font"

    fun apply(v: View, ctx: Context, i: Int) {
        if (v is TextView && v.tag != KEEP) {
            val wasBold = v.typeface?.isBold == true
            v.typeface = if (wasBold) bold(ctx, i) else typeface(ctx, i)
        }
        if (v is ViewGroup) for (k in 0 until v.childCount) apply(v.getChildAt(k), ctx, i)
    }
}
