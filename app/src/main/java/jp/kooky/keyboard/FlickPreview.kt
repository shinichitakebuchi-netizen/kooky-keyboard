package jp.kooky.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

// 設定画面の「フリックの向きの見本」
// 扇形の奥側・真ん中・親指側の3つのキーに、今の設定でのフリックの上下左右の矢印を描く
class FlickPreview(context: Context) : View(context), SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
    private val d = resources.displayMetrics.density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Iro.RIKYUNEZUMI; style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Iro.KAMENOZOKI_70; strokeWidth = 2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Iro.BENIHI; strokeWidth = 2.5f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Iro.KAMENOZOKI_70; style = Paint.Style.FILL }
    private val upHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Iro.BENIHI; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Iro.TORINOKO; textSize = 18f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = false
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Iro.KAMENOZOKI_70; textSize = 11f * d; textAlign = Paint.Align.CENTER
    }
    private val degPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Iro.BENIHI; textSize = 13f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (150 * d).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "tilt_deg" || key == "tilt_dir" || key == "flick_pct" || key == "tilt_on") invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Iro.AI)
        val deg = prefs.getInt("tilt_deg", 0).coerceIn(0, 12)
        val on = prefs.getBoolean("tilt_on", true) && deg > 0
        val left = prefs.getString("tilt_dir", "right") == "left"
        val pct = prefs.getInt("flick_pct", 100).coerceIn(0, 200) / 100f

        // キーボードと同じ計算：親指側の端からの距離で列の角度が決まる（5列のうち 1・3・5列目）
        val cols = listOf(0, 2, 4)
        val names = if (left) listOf("親指側", "真ん中", "奥側") else listOf("奥側", "真ん中", "親指側")
        val labels = listOf("あ", "な", "は")
        val w = width.toFloat()
        val cellW = w / 3
        val ks = 46f * d
        val cy = 62f * d
        for ((i, c) in cols.withIndex()) {
            val cx = (c + 0.5f) / 5f
            val dist = if (left) cx else cx - 1f
            val a = if (on) deg * dist * pct else 0f
            val x = cellW * (i + 0.5f)

            canvas.save()
            canvas.translate(x, cy)
            canvas.rotate(a)
            // 下・左・右の矢印（灰）
            arrow(canvas, 0f, 1f, linePaint, headPaint)
            arrow(canvas, -1f, 0f, linePaint, headPaint)
            arrow(canvas, 1f, 0f, linePaint, headPaint)
            canvas.restore()

            // キー（キー自体はまっすぐ）
            canvas.drawRoundRect(RectF(x - ks / 2, cy - ks / 2, x + ks / 2, cy + ks / 2), 2f * d, 2f * d, keyPaint)
            canvas.drawText(labels[i], x, cy + 6f * d, textPaint)

            // 上の矢印（オレンジ）はキーの上に重ねる
            canvas.save()
            canvas.translate(x, cy)
            canvas.rotate(a)
            arrow(canvas, 0f, -1f, upPaint, upHeadPaint)
            canvas.restore()

            val shown = Math.round(a * 10) / 10f
            canvas.drawText(if (shown == 0f) "0°" else "${if (shown > 0) "+" else ""}$shown°", x, cy + 58f * d, degPaint)
            canvas.drawText(names[i], x, cy + 74f * d, subPaint)
        }
        if (!on) canvas.drawText("角度が「なし」かオフのときは、まっすぐの上下左右", w / 2, 14f * d, subPaint)
    }

    // キーの中心から外へ向かう矢印（dx,dy は向き）
    private fun arrow(canvas: Canvas, dx: Float, dy: Float, line: Paint, head: Paint) {
        val r0 = 26f * d
        val r1 = 44f * d
        canvas.drawLine(dx * r0, dy * r0, dx * r1, dy * r1, line)
        val hx = dx * (r1 + 6f * d)
        val hy = dy * (r1 + 6f * d)
        val px = -dy
        val py = dx
        val s = 5f * d
        val path = Path().apply {
            moveTo(hx, hy)
            lineTo(dx * r1 + px * s, dy * r1 + py * s)
            lineTo(dx * r1 - px * s, dy * r1 - py * s)
            close()
        }
        canvas.drawPath(path, head)
    }
}
