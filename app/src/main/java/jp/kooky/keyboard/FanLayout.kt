package jp.kooky.keyboard

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

// キーの段（左列・文字キー・右列）を入れる箱
// 扇形にするとキーが元の場所からずれるため、Android標準のタッチの振り分けだと
// キーの端（元の行・列からはみ出した部分）が反応しない。
// 扇形のときだけ、見た目どおりの位置でキーを探して、そのキーへ直接タッチを渡す。
class FanLayout(context: Context) : LinearLayout(context) {

    // 扇形が有効か
    var active: () -> Boolean = { false }
    // 対象のキー
    var keys: () -> List<View> = { emptyList() }
    // この上に重なっている部品（音声パネルなど）。ここに触れたときは標準の振り分けに任せる
    var overlays: () -> List<View> = { emptyList() }

    private var target: View? = null
    private val rect = Rect()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!active()) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = null
                val x = ev.x
                val y = ev.y
                if (overlays().any { it.isShown && hit(it, x, y, 0f) }) return super.dispatchTouchEvent(ev)
                target = find(x, y)
                val t = target ?: return super.dispatchTouchEvent(ev)
                return forward(t, ev)
            }
            else -> {
                val t = target ?: return super.dispatchTouchEvent(ev)
                // 2本目以降の指は無視（キーは1本指で操作）
                if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_UP) return true
                val r = forward(t, ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) target = null
                return r
            }
        }
    }

    // 見た目どおりのキーの範囲（ずらし・縮小を反映）に入っているか
    private fun hit(v: View, x: Float, y: Float, pad: Float): Boolean {
        rect.set(0, 0, v.width, v.height)
        offsetDescendantRectToMyCoords(v, rect)
        val cx = rect.exactCenterX() + v.translationX
        val cy = rect.exactCenterY() + v.translationY
        val hw = v.width * v.scaleX / 2 + pad
        val hh = v.height * v.scaleY / 2 + pad
        return x in (cx - hw)..(cx + hw) && y in (cy - hh)..(cy + hh)
    }

    // 触れた場所のキー。キーの間のすき間なら、一番近いキー
    private fun find(x: Float, y: Float): View? {
        var best: View? = null
        var bestD = Float.MAX_VALUE
        for (k in keys()) {
            if (!k.isShown || k.width == 0) continue
            rect.set(0, 0, k.width, k.height)
            offsetDescendantRectToMyCoords(k, rect)
            val cx = rect.exactCenterX() + k.translationX
            val cy = rect.exactCenterY() + k.translationY
            val hw = k.width * k.scaleX / 2
            val hh = k.height * k.scaleY / 2
            if (x in (cx - hw)..(cx + hw) && y in (cy - hh)..(cy + hh)) return k
            // 横長・縦長の差をならして距離を比べる
            val dx = (x - cx) / hw
            val dy = (y - cy) / hh
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        // 遠すぎる場所（キーの1.6個分以上離れている）は反応させない
        return if (bestD < 2.6f * 2.6f) best else null
    }

    // キー自身の座標に直して渡す
    private fun forward(k: View, ev: MotionEvent): Boolean {
        rect.set(0, 0, k.width, k.height)
        offsetDescendantRectToMyCoords(k, rect)
        val cx = rect.exactCenterX() + k.translationX
        val cy = rect.exactCenterY() + k.translationY
        val sx = if (k.scaleX == 0f) 1f else k.scaleX
        val sy = if (k.scaleY == 0f) 1f else k.scaleY
        val e = MotionEvent.obtain(ev)
        e.setLocation((ev.x - cx) / sx + k.width / 2f, (ev.y - cy) / sy + k.height / 2f)
        val r = k.dispatchTouchEvent(e)
        e.recycle()
        return r || true
    }
}
