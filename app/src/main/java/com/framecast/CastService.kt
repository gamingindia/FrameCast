package com.framecast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Shared state the activity polls to show status. */
object CastState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
}

/**
 * Captures the screen, shrinks each frame to the panel size, JPEG-encodes it and sends it to the
 * photo frame (ws://<ip>:81). Protocol: one binary JPEG per message, the frame answers "K" after each.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.framecast.START"
        const val ACTION_STOP = "com.framecast.STOP"
        const val PW = 128           // panel width
        const val PH = 160           // panel height
        const val MAX_BYTES = 14000  // the frame's WebSocket library rejects messages above ~15 KB
        const val CHANNEL = "cast"
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var client: OkHttpClient? = null

    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var ready = false      // true once the frame acked the previous frame
    @Volatile private var stopping = false

    private var lastSend = 0L
    private var lastJpeg: ByteArray? = null
    private var quality = 70
    private var minInterval = 50L
    private var mode = "stretch"
    private var url = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown("Stopped"); return START_NOT_STICKY }
        if (intent?.action != ACTION_START || CastState.running) return START_NOT_STICKY

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")
        url = intent.getStringExtra("url") ?: return START_NOT_STICKY
        mode = intent.getStringExtra("mode") ?: "stretch"
        quality = intent.getIntExtra("quality", 70).coerceIn(15, 95)
        minInterval = 1000L / intent.getIntExtra("fps", 20).coerceIn(1, 60)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        startForegroundCompat()           // must happen before getMediaProjection on Android 14+
        CastState.running = true
        CastState.status = "Starting…"

        thread = HandlerThread("cast").also { it.start() }
        handler = Handler(thread!!.looper)
        try {
            setupCapture(code, data)
        } catch (e: Exception) {
            shutdown("Capture error: ${e.message}")
            return START_NOT_STICKY
        }
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
        connect()
        handler?.post(tick)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen cast", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Casting your screen to the frame")
            .setContentText("Open FrameCast to stop")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler?.post { shutdown("Stopped by system") } }
        }, handler)

        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val scale = 320f / minOf(dm.widthPixels, dm.heightPixels)
        val cw = maxOf(2, (dm.widthPixels * scale).toInt())
        val ch = maxOf(2, (dm.heightPixels * scale).toInt())
        val rd = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = proj.createVirtualDisplay(
            "framecast", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, handler
        )
    }

    // ---------------- network ----------------
    private fun connect() {
        if (stopping) return
        CastState.status = "Connecting to $url …"
        ready = false
        open = false
        ws = client!!.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                lastSend = SystemClock.elapsedRealtime()
                CastState.status = "Connected, waiting for the frame…"
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "K") { ready = true; CastState.status = "Casting" }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnect(webSocket, "Disconnected") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnect(webSocket, "Connection failed: ${t.message}")
            }
        })
    }

    private fun reconnect(from: WebSocket, why: String) {
        if (stopping || from !== ws) return
        open = false
        ready = false
        CastState.status = "$why – retrying…"
        handler?.postDelayed({ connect() }, 1500)
    }

    private fun send(jpeg: ByteArray) {
        val s = ws ?: return
        if (s.send(jpeg.toByteString())) {
            ready = false
            lastSend = SystemClock.elapsedRealtime()
            lastJpeg = jpeg
        }
    }

    // ---------------- capture loop ----------------
    private val tick = object : Runnable {
        override fun run() {
            if (stopping) return
            try { step() } catch (e: Exception) { CastState.status = "Error: ${e.message}" }
            handler?.postDelayed(this, 15)
        }
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        if (!ready) {
            if (open && now - lastSend > 4000) ready = true   // ack lost: try again
            else return
        }
        if (now - lastSend < minInterval) return
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                val plane = img.planes[0]
                val rowPad = plane.rowStride - plane.pixelStride * img.width
                val bmp = Bitmap.createBitmap(
                    img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                val jpeg = encode(bmp, img.width, img.height)
                bmp.recycle()
                send(jpeg)
            } finally {
                img.close()
            }
        } else {
            // static screen: re-send the last frame every second so the frame doesn't time the cast out
            val last = lastJpeg
            if (last != null && now - lastSend >= 1000) send(last)
        }
    }

    private fun encode(src: Bitmap, w: Int, h: Int): ByteArray {
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
                c.drawColor(Color.BLACK)
                val s = minOf(PW.toFloat() / w, PH.toFloat() / h)
                val dw = (w * s).toInt()
                val dh = (h * s).toInt()
                val dl = (PW - dw) / 2
                val dt = (PH - dh) / 2
                c.drawBitmap(src, Rect(0, 0, w, h), Rect(dl, dt, dl + dw, dt + dh), p)
            }
            else -> c.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, PW, PH), p)   // stretch
        }
        var q = quality
        while (true) {
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, q, bos)    // baseline JPEG, what TJpg_Decoder needs
            if (bos.size() <= MAX_BYTES || q <= 15) {
                out.recycle()
                return bos.toByteArray()
            }
            q -= 5
        }
    }

    // ---------------- teardown ----------------
    private fun shutdown(msg: String) {
        if (stopping) return
        stopping = true
        try { handler?.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        thread?.quitSafely()
        CastState.running = false
        CastState.status = msg
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown("Stopped")
        super.onDestroy()
    }
}
