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
    private const val ANIM_FILE = "kb_bg.anim"
    private const val MAX_SIDE = 1440
    private const val MAX_ANIM_BYTES = 20L * 1024 * 1024

    fun file(context: Context) = File(context.filesDir, FILE)
    fun animFile(context: Context) = File(context.filesDir, ANIM_FILE)

    // 動く画像（GIF・アニメWebP）が設定されているか
    fun isAnimated(context: Context): Boolean = has(context) && animFile(context).exists()

    // GIF・WebPなら元のデータをそのまま保存（動きを残すため）
    private fun saveAnimIfAny(context: Context, uri: Uri): Boolean {
        val cr = context.contentResolver
        val type = cr.getType(uri) ?: ""
        val head = ByteArray(12)
        val n = cr.openInputStream(uri)?.use { it.read(head) } ?: 0
        val isGif = type == "image/gif" || (n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "GIF8")
        val isWebp = type == "image/webp" ||
            (n >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP")
        val dest = animFile(context)
        if (!isGif && !isWebp) {
            dest.delete()
            return false
        }
        val tmp = File(context.filesDir, "$ANIM_FILE.tmp")
        var total = 0L
        cr.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_ANIM_BYTES) break
                    out.write(buf, 0, r)
                }
            }
        }
        if (total > MAX_ANIM_BYTES) {
            tmp.delete()
            dest.delete()
            throw TooBig()
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        return true
    }

    class TooBig : Exception("GIFが大きすぎるよ（20MBまで）")

    fun has(context: Context): Boolean =
        context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getLong("bg_image", 0L) > 0 &&
            file(context).exists()

    // 選んだ画像を縮小・向き補正して保存。成功したら設定の番号を更新（キーボードが作り直される）
    fun save(context: Context, uri: Uri): Boolean {
        return try {
            // 動く画像はそのまま保存。止まった画像（1コマ目）も、古い端末用に下で作っておく
            saveAnimIfAny(context, uri)
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
        } catch (e: TooBig) {
            throw e
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        animFile(context).delete()
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

// 動く画像を読み込む（Android 9以降）。大きすぎる場合は縮小して読み込む
fun loadAnimatedBg(context: Context): android.graphics.drawable.AnimatedImageDrawable? {
    if (android.os.Build.VERSION.SDK_INT < 28 || !BgImage.isAnimated(context)) return null
    return try {
        val src = android.graphics.ImageDecoder.createSource(BgImage.animFile(context))
        val d = android.graphics.ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > 1080) {
                val sc = 1080f / big
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * sc).toInt()),
                    maxOf(1, (info.size.height * sc).toInt())
                )
            }
        }
        (d as? android.graphics.drawable.AnimatedImageDrawable)?.apply {
            repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

// 動く画像を枠いっぱいに表示（はみ出す分は切って中央を表示）。コマ送りの再描画は親に伝える
class CropAnimDrawable(private val inner: Drawable) : Drawable(), Drawable.Callback {

    init {
        inner.callback = this
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val iw = inner.intrinsicWidth.toFloat()
        val ih = inner.intrinsicHeight.toFloat()
        val vw = bounds.width().toFloat()
        val vh = bounds.height().toFloat()
        if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) {
            inner.bounds = bounds
            return
        }
        val sc = maxOf(vw / iw, vh / ih)
        val w = (iw * sc).toInt()
        val h = (ih * sc).toInt()
        val l = bounds.left + ((vw - w) / 2).toInt()
        val t = bounds.top + ((vh - h) / 2).toInt()
        inner.setBounds(l, t, l + w, t + h)
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(bounds)
        inner.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
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
