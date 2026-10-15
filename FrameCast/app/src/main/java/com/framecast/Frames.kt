package com.framecast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Bitmap helpers shared by photos and notes (panel is 128x160). */
object Frames {
    const val PW = 128
    const val PH = 160

    /** Scale a bitmap to the panel: "stretch", "crop" (fill, cut edges) or "fit" (black bars). */
    fun toPanel(src: Bitmap, mode: String): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        when (mode) {
            "crop" -> {
                val s = maxOf(PW.toFloat() / w, PH.toFloat() / h)
                val sw = PW / s
                val sh = PH / s
                val l = (w - sw) / 2f
                val t = (h - sh) / 2f
                c.drawBitmap(src, Rect(l.toInt(), t.toInt(), (l + sw).toInt(), (t + sh).toInt()), Rect(0, 0, PW, PH), p)
            }
            "fit" -> {
                c.drawColor(0xFF000000.toInt())
                val s = minOf(PW.toFloat() / w, PH.toFloat() / h)
                val dw = (w * s).toInt()
                val dh = (h * s).toInt()
                val dl = (PW - dw) / 2
                val dt = (PH - dh) / 2
                c.drawBitmap(src, Rect(0, 0, w, h), Rect(dl, dt, dl + dw, dt + dh), p)
            }
            else -> c.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, PW, PH), p)
        }
        return out
    }

    /** Baseline JPEG no bigger than maxBytes (quality is lowered until it fits). */
    fun jpeg(bmp: Bitmap, quality: Int, maxBytes: Int): ByteArray {
        var q = quality
        while (true) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            if (bos.size() <= maxBytes || q <= 15) return bos.toByteArray()
            q -= 5
        }
    }

    /** Draw centered, word-wrapped text (emoji work) on a solid background, shrinking the font until it fits. */
    fun renderNote(text: String, bg: Int, fg: Int, startSize: Int): Bitmap {
        val bmp = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = fg
        fun build(sz: Int): StaticLayout {
            tp.textSize = sz.toFloat()
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, PW - 12)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        }
        var size = startSize
        var layout = build(size)
        while (layout.height > PH - 8 && size > 8) {
            size--
            layout = build(size)
        }
        c.save()
        c.translate(6f, ((PH - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(c)
        c.restore()
        return bmp
    }

    /** Decode a gallery image (EXIF rotation handled), roughly 320 px on the short side. */
    fun decode(ctx: Context, uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = minOf(info.size.width, info.size.height)
            dec.setTargetSampleSize(maxOf(1, m / 320))
        }
    } catch (e: Exception) {
        null
    }
}
