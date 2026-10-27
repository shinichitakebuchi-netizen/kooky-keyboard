package jp.kooky.keyboard

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

// 二本線のつまみを長押しすると行が浮いて、指で上下に動かして並べ替えられる
// rows は並び順どおりに add する。指を離したら onMove(元の位置, 新しい位置) を呼ぶ
class DragSort(private val onMove: (from: Int, to: Int) -> Unit) {

    private val rows = ArrayList<View>()
    private var dragging = false
    private var from = -1
    private var target = -1
    private var startRawY = 0f
    private var lastRawY = 0f
    private var step = 0f
    private var centers = FloatArray(0)
    private var pending: Runnable? = null

    fun add(row: View, handle: View) {
        rows.add(row)
        val slop = ViewConfiguration.get(handle.context).scaledTouchSlop
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = e.rawY
                    lastRawY = e.rawY
                    val r = Runnable { startDrag(row, v) }
                    pending = r
                    v.postDelayed(r, 250)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastRawY = e.rawY
                    if (dragging) {
                        move(e.rawY)
                    } else if (abs(e.rawY - startRawY) > slop) {
                        // 長押しになる前に動いたらスクロールとして扱う
                        cancelPending(v)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPending(v)
                    if (dragging) drop()
                }
            }
            true
        }
    }

    private fun cancelPending(v: View) {
        pending?.let { v.removeCallbacks(it) }
        pending = null
    }

    private fun startDrag(row: View, handle: View) {
        pending = null
        from = rows.indexOf(row)
        if (from < 0) return
        dragging = true
        target = from
        startRawY = lastRawY
        centers = FloatArray(rows.size) { rows[it].top + rows[it].height / 2f }
        val gap = if (rows.size > 1) (rows[1].top - rows[0].bottom).toFloat() else 0f
        step = row.height + gap
        // 親（スクロール）に指の動きを取られないようにする
        handle.parent?.requestDisallowInterceptTouchEvent(true)
        row.parent?.requestDisallowInterceptTouchEvent(true)
        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        row.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.92f).setDuration(120).start()
        row.elevation = 16f * row.resources.displayMetrics.density
    }

    private fun move(rawY: Float) {
        val row = rows.getOrNull(from) ?: return
        val dy = rawY - startRawY
        row.translationY = dy
        val center = centers[from] + dy
        var t = from
        for (i in from + 1 until rows.size) if (center > centers[i]) t = i
        for (i in from - 1 downTo 0) if (center < centers[i]) t = i
        if (t != target) {
            target = t
            row.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        // ほかの行をずらして、入る場所を空ける
        for (j in rows.indices) {
            if (j == from) continue
            val shift = when {
                from < target && j in (from + 1)..target -> -step
                target < from && j in target until from -> step
                else -> 0f
            }
            if (rows[j].translationY != shift) rows[j].animate().translationY(shift).setDuration(120).start()
        }
    }

    private fun drop() {
        dragging = false
        for (v in rows) {
            v.animate().cancel()
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 1f
            v.elevation = 0f
        }
        val f = from
        val t = target
        from = -1
        target = -1
        if (f >= 0 && t >= 0 && f != t) onMove(f, t)
    }
}
