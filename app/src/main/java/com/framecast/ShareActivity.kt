package com.framecast

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Target of the Android "Share" sheet: sends the shared image(s) to the frame, then closes. */
class ShareActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        val i = intent
        if (i?.action == Intent.ACTION_SEND) {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
            if (u != null) uris.add(u)
        } else if (i?.action == Intent.ACTION_SEND_MULTIPLE) {
            val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (l != null) uris.addAll(l)
        }
        if (uris.isEmpty()) { finish(); return }

        Toast.makeText(this, "Sending ${uris.size} image(s) to the frame…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val msg = PhotoSender.send(app, uris)
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
