package com.framecast

import android.content.Context
import android.net.Uri

/** Sends gallery images (and GIFs) to the frame's photo gallery. */
object PhotoSender {
    fun send(ctx: Context, uris: List<Uri>): String {
        var ok = 0
        var err = ""
        for (u in uris) {
            val r = sendOne(ctx, u)
            if (r.ok) ok++ else if (err.isEmpty()) err = r.msg
        }
        return if (err.isEmpty()) "Sent $ok photo(s) to the frame" else "Sent $ok of ${uris.size}. $err"
    }

    fun sendOne(ctx: Context, uri: Uri): FrameApi.Result {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime == "image/gif") {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return FrameApi.Result(false, "Cannot read the GIF")
            if (bytes.size > 300000) return FrameApi.Result(false, "GIF is over 300 KB")
            // sent as is: the frame rejects GIFs larger than its 128x160 panel
            return FrameApi.upload(ctx, "/gif", "g", "a.gif", "image/gif", bytes)
        }
        val bmp = Frames.decode(ctx, uri) ?: return FrameApi.Result(false, "Cannot read the image")
        val mode = FrameApi.prefs(ctx).getString("pmode", "crop") ?: "crop"
        val jpeg = Frames.jpeg(Frames.toPanel(bmp, mode), 88, 38000)
        return FrameApi.upload(ctx, "/up", "p", "p.jpg", "image/jpeg", jpeg)
    }
}
