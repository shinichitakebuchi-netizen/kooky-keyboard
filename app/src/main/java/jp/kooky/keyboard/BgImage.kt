package jp.kooky.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import java.io.File

// キーボードの背景画像（ギャラリーから選んだ画像を縮小して端末内に保存）
object BgImage {

    private const val FILE = "kb_bg.jpg"
    private const val MAX_SIDE = 1440

    fun file(context: Context) = File(context.filesDir, FILE)

    fun has(context: Context): Boolean =
        context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getLong("bg_image", 0L) > 0 &&
            file(context).exists()

    // 選んだ画像を縮小・向き補正して保存。成功したら設定の番号を更新（キーボードが作り直される）
    fun save(context: Context, uri: Uri): Boolean {
        return try {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

            // スマホのカメラ写真は向きの情報を持っているので、それに合わせて回す
            val rotate = try {
                cr.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }

            val big = maxOf(bmp.width, bmp.height)
            val scale = if (big > MAX_SIDE) MAX_SIDE.toFloat() / big else 1f
            if (rotate != 0f || scale != 1f) {
                val m = Matrix().apply {
                    postScale(scale, scale)
                    postRotate(rotate)
                }
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out != bmp) bmp.recycle()
                bmp = out
            }

            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            val dest = file(context)
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)

            context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).edit()
                .putLong("bg_image", System.currentTimeMillis())
                .apply()
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).edit()
            .putLong("bg_image", 0L)
            .apply()
    }

    fun load(context: Context): Bitmap? = try {
        if (has(context)) BitmapFactory.decodeFile(file(context).path) else null
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

// 画像を枠いっぱいに、はみ出す分は上下（左右）を切って中央に表示する
// 大きさの主張をしないので、キーボードの高さには影響しない
class CropDrawable(private val bmp: Bitmap) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val vw = bounds.width().toFloat()
        val vh = bounds.height().toFloat()
        if (vw <= 0 || vh <= 0) return
        val viewRatio = vw / vh
        if (bw / bh > viewRatio) {
            val w = (bh * viewRatio).toInt()
            val x = ((bw - w) / 2).toInt()
            src.set(x, 0, x + w, bh.toInt())
        } else {
            val h = (bw / viewRatio).toInt()
            val y = ((bh - h) / 2).toInt()
            src.set(0, y, bw.toInt(), y + h)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bmp, src, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
