package com.framecast

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** HTTP calls to the frame. If the saved IP stops answering, the frame is looked up again over mDNS. */
object FrameApi {
    class Result(val ok: Boolean, val msg: String, val net: Boolean = false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun hostOf(ip: String) = ip.removePrefix("ws://").removePrefix("http://").trimEnd('/').substringBefore(':')

    private fun attempt(ip: String, build: (String) -> Request): Result = try {
        client.newCall(build("http://$ip")).execute().use { r ->
            val body = r.body?.string()?.take(160) ?: ""
            if (r.isSuccessful) Result(true, "OK") else Result(false, "Frame: $body (${r.code})")
        }
    } catch (e: Exception) {
        Result(false, e.message ?: "network error", true)
    }

    fun call(ctx: Context, build: (String) -> Request): Result {
        val app = ctx.applicationContext
        val ip = hostOf(prefs(app).getString("ip", "") ?: "")
        var r = if (ip.isNotEmpty()) attempt(ip, build) else Result(false, "No frame IP set", true)
        if (!r.ok && r.net) {
            val found = Discovery.find(app, 4000)
            if (found != null && found != ip) {
                prefs(app).edit().putString("ip", found).apply()
                r = attempt(found, build)
            } else if (found == null) {
                r = Result(false, "Frame not reachable (its WiFi may be off right now)", true)
            }
        }
        return r
    }

    fun command(ctx: Context, name: String): Result =
        call(ctx) { base -> Request.Builder().url("$base/$name").build() }

    fun upload(ctx: Context, path: String, field: String, fileName: String, mime: String, bytes: ByteArray): Result =
        call(ctx) { base ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(field, fileName, bytes.toRequestBody(mime.toMediaType()))
                .build()
            Request.Builder().url(base + path).post(body).build()
        }

    fun note(ctx: Context, jpeg: ByteArray, seconds: Int): Result =
        upload(ctx, "/note?s=$seconds", "n", "n.jpg", "image/jpeg", jpeg)
}
