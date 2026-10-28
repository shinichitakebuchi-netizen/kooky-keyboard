package jp.kooky.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

// 背景画像のトリミング画面
// キーボードと同じ形の枠が固定で、画像を指で動かす・2本指で拡大縮小して、枠に入る範囲を決める
@SuppressLint("ViewConstructor")
class CropView(
    context: Context,
    private val bmp: Bitmap,
    private val frameRatio: Float,
    private val initCrop: FloatArray?
) : View(context) {

    private val frame = RectF()
    private var s = 1f      // 画像の拡大率
    private var tx = 0f     // 画像の左上の位置
    private var ty = 0f
    private var minS = 1f
    private var ready = false

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F28C28")
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val dimPath = Path()

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (s * d.scaleFactor).coerceIn(minS, minS * 8f)
            val k = ns / s
            // 指の間を中心に拡大縮小
            tx = d.focusX - (d.focusX - tx) * k
            ty = d.focusY - (d.focusY - ty) * k
            s = ns
            clampPos()
            invalidate()
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 16f * resources.displayMetrics.density
        var fw = w - pad * 2
        var fh = fw / frameRatio
        if (fh > h * 0.7f) {
            fh = h * 0.7f
            fw = fh * frameRatio
        }
        val l = (w - fw) / 2
        val t = (h - fh) / 2
        frame.set(l, t, l + fw, t + fh)
        minS = maxOf(fw / bmp.width, fh / bmp.height)
        if (!ready) {
            // 前回のトリミング範囲（なければ中央）から始める
            val src = cropSrc(bmp.width.toFloat(), bmp.height.toFloat(), fw, fh, initCrop)
            s = (fw / src.width()).coerceAtLeast(minS)
            tx = frame.left - src.left * s
            ty = frame.top - src.top * s
            ready = true
        }
        clampPos()
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        dimPath.addRect(frame, Path.Direction.CW)
    }

    // 枠の中に画像の外（すき間）が出ないようにする
    private fun clampPos() {
        if (s < minS) s = minS
        val iw = bmp.width * s
        val ih = bmp.height * s
        tx = tx.coerceIn(frame.right - iw, frame.left)
        ty = ty.coerceIn(frame.bottom - ih, frame.top)
    }

    fun reset() {
        val src = cropSrc(bmp.width.toFloat(), bmp.height.toFloat(), frame.width(), frame.height(), null)
        s = frame.width() / src.width()
        tx = frame.left - src.left * s
        ty = frame.top - src.top * s
        clampPos()
        invalidate()
    }

    // 枠に入っている範囲を、画像に対する割合（l,t,r,b）で返す
    fun result(): FloatArray {
        val l = ((frame.left - tx) / s / bmp.width).coerceIn(0f, 1f)
        val t = ((frame.top - ty) / s / bmp.height).coerceIn(0f, 1f)
        val r = ((frame.right - tx) / s / bmp.width).coerceIn(0f, 1f)
        val b = ((frame.bottom - ty) / s / bmp.height).coerceIn(0f, 1f)
        return floatArrayOf(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1428"))
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(bmp, 0f, 0f, imgPaint)
        canvas.restore()
        canvas.drawPath(dimPath, dimPaint)
        // 3分割の目安線
        for (i in 1..2) {
            val x = frame.left + frame.width() * i / 3
            val y = frame.top + frame.height() * i / 3
            canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint)
        }
        canvas.drawRect(frame, framePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = e.getPointerId(0)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 離した指が動かしていた指なら、残った指に切り替える
                val idx = e.actionIndex
                if (e.getPointerId(idx) == pointer) {
                    val ni = if (idx == 0) 1 else 0
                    pointer = e.getPointerId(ni)
                    lastX = e.getX(ni)
                    lastY = e.getY(ni)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointer)
                if (idx >= 0) {
                    val x = e.getX(idx)
                    val y = e.getY(idx)
                    if (!scaler.isInProgress) {
                        tx += x - lastX
                        ty += y - lastY
                        clampPos()
                        invalidate()
                    }
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = -1
        }
        return true
    }
}
