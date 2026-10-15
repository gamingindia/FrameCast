package com.framecast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val modes = listOf("stretch", "crop", "fit")
    private val themes = listOf(
        Pair("#1A1A2E", "#FFFFFF"), Pair("#F5F5F5", "#222222"), Pair("#FF6F91", "#FFFFFF"),
        Pair("#2D7FF9", "#FFFFFF"), Pair("#2E7D32", "#FFFFFF"), Pair("#000000", "#00FF66")
    )
    private val themeNames = listOf("Midnight", "Paper", "Pink", "Blue", "Green", "Terminal")
    private val durations = listOf(30, 120, 600, 3600)
    private val durationNames = listOf("30 seconds", "2 minutes", "10 minutes", "1 hour")

    private lateinit var ipEdit: EditText
    private lateinit var castGroup: RadioGroup
    private lateinit var photoGroup: RadioGroup
    private lateinit var msg: TextView
    private lateinit var castStatus: TextView
    private lateinit var button: Button
    private lateinit var noteText: EditText
    private lateinit var themeSpin: Spinner
    private lateinit var durSpin: Spinner
    private lateinit var autoSwitch: Switch
    private var quality = 70
    private var fps = 20
    private var noteSize = 22
    private var pendingUrl = ""
    private var pendingMode = "stretch"

    private val poll = object : Runnable {
        override fun run() {
            castStatus.text = CastState.status
            button.text = if (CastState.running) "Stop casting" else "Start casting"
            ui.postDelayed(this, 500)
        }
    }

    private fun mediaPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPerm() = checkSelfPermission(mediaPerm()) == PackageManager.PERMISSION_GRANTED

    private fun cleanIp() = ipEdit.text.toString().trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')

    private fun saveIp() {
        FrameApi.prefs(this).edit().putString("ip", cleanIp()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = FrameApi.prefs(this)
        quality = prefs.getInt("q", 70)
        fps = prefs.getInt("fps", 20)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, pad / 2, 0, 0)
        }
        fun section(t: String) {
            root.addView(label(t, 20f, true).apply { setPadding(0, pad, 0, 0) })
        }
        fun btn(t: String, onClick: () -> Unit): Button {
            val b = Button(this).apply { text = t; setOnClickListener { onClick() } }
            root.addView(b)
            return b
        }
        fun seek(title: String, lo: Int, hi: Int, init: Int, unit: String, set: (Int) -> Unit) {
            val tv = label("$title: $init$unit")
            val sb = SeekBar(this)
            sb.max = hi - lo
            sb.progress = init - lo
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    set(lo + p)
                    tv.text = "$title: ${lo + p}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(tv)
            root.addView(sb)
        }
        fun spinner(items: List<String>) = Spinner(this).apply {
            val a = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = a
        }
        fun modeGroup(base: Int, selected: String) = RadioGroup(this).also { g ->
            val names = listOf("Stretch (whole screen, distorts)", "Crop (fills the panel, cuts edges)", "Fit (keeps shape, black bars)")
            modes.forEachIndexed { i, _ -> g.addView(RadioButton(this).apply { id = base + i; text = names[i] }) }
            g.check(base + modes.indexOf(selected).coerceAtLeast(0))
        }

        // ---------- connection ----------
        root.addView(label("FrameCast", 26f, true))
        root.addView(label("Frame IP address (same WiFi as this phone)"))
        ipEdit = EditText(this).apply {
            setText(prefs.getString("ip", "192.168.1.36"))
            setSingleLine()
        }
        root.addView(ipEdit)
        btn("Find frame automatically") {
            msg.text = "Searching…"
            val app = applicationContext
            Thread {
                val ip = Discovery.find(app, 6000)
                runOnUiThread {
                    if (ip != null) {
                        ipEdit.setText(ip)
                        saveIp()
                        msg.text = "Found the frame at $ip"
                    } else {
                        msg.text = "No frame found (is its WiFi on? press reset on the frame)"
                    }
                }
            }.start()
        }
        msg = label("")
        root.addView(msg)

        // ---------- screen cast ----------
        section("Screen cast")
        castGroup = modeGroup(100, prefs.getString("mode", "stretch") ?: "stretch")
        root.addView(castGroup)
        seek("JPEG quality", 30, 90, quality, "") { quality = it }
        seek("Max frame rate", 5, 30, fps, " fps") { fps = it }
        button = btn("Start casting") { onCastButton() }
        castStatus = label("Stopped")
        root.addView(castStatus)

        // ---------- notes ----------
        section("Notes")
        noteText = EditText(this).apply {
            hint = "Type a note… emoji welcome"
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        root.addView(noteText)
        root.addView(label("Colors"))
        themeSpin = spinner(themeNames)
        root.addView(themeSpin)
        root.addView(label("Show for"))
        durSpin = spinner(durationNames)
        root.addView(durSpin)
        seek("Text size", 10, 40, noteSize, " px") { noteSize = it }
        btn("Send note to frame") { onSendNote() }
        btn("Dismiss note on frame") {
            val app = applicationContext
            Thread {
                val r = FrameApi.command(app, "notedel")
                runOnUiThread { msg.text = if (r.ok) "Note dismissed" else r.msg }
            }.start()
        }

        // ---------- photos ----------
        section("Photos")
        root.addView(label("How photos are fitted to the panel"))
        photoGroup = modeGroup(200, prefs.getString("pmode", "crop") ?: "crop")
        photoGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("pmode", modes[(id - 200).coerceIn(0, 2)]).apply()
        }
        root.addView(photoGroup)
        btn("Send photos from gallery…") {
            saveIp()
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, 5)
        }
        autoSwitch = Switch(this).apply {
            text = "Auto-send new camera photos"
            isChecked = prefs.getBoolean("auto", false) && hasMediaPerm()
            setOnCheckedChangeListener { _, on -> if (on) enableAuto() else disableAuto() }
        }
        root.addView(autoSwitch)
        root.addView(label("The frame's WiFi sleeps to save battery, so photos are sent when it wakes (about every 10 minutes), or right away if it is awake.", 13f))
        root.addView(label("Tip: share any photo from your gallery to \"Send to frame\".", 13f))

        // ---------- web tools ----------
        section("Pixel art and web pages")
        btn("Open pixel editor") { openPage("draw") }
        btn("Open notes page") { openPage("notes") }
        btn("Open gallery page") { openPage("") }
        root.addView(label("Widget: long-press the home screen → Widgets → FrameCast (Prev / Next buttons).", 13f))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        if (prefs.getBoolean("auto", false) && hasMediaPerm()) PhotoJob.schedule(this)   // re-arm after reboot / update
        ui.post(poll)
    }

    // ---------- actions ----------
    private fun openPage(path: String) {
        val ip = cleanIp()
        if (ip.isEmpty()) { msg.text = "Enter the frame's IP address"; return }
        saveIp()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${FrameApi.hostOf(ip)}/$path")))
    }

    private fun onSendNote() {
        val text = noteText.text.toString().trim()
        if (text.isEmpty()) { msg.text = "Type a note first"; return }
        saveIp()
        val (bg, fg) = themes[themeSpin.selectedItemPosition.coerceIn(0, themes.size - 1)]
        val secs = durations[durSpin.selectedItemPosition.coerceIn(0, durations.size - 1)]
        val jpeg = Frames.jpeg(Frames.renderNote(text, Color.parseColor(bg), Color.parseColor(fg), noteSize), 90, 14000)
        msg.text = "Sending note…"
        val app = applicationContext
        Thread {
            val r = FrameApi.note(app, jpeg, secs)
            runOnUiThread { msg.text = if (r.ok) "Note shown on the frame" else r.msg }
        }.start()
    }

    private fun enableAuto() {
        if (!hasMediaPerm()) {
            requestPermissions(arrayOf(mediaPerm()), 3)
            return
        }
        FrameApi.prefs(this).edit()
            .putBoolean("auto", true)
            .putLong("last_added", System.currentTimeMillis() / 1000)   // only photos taken from now on
            .apply()
        saveIp()
        PhotoJob.schedule(this)
        msg.text = "New camera photos will be sent to the frame"
    }

    private fun disableAuto() {
        FrameApi.prefs(this).edit().putBoolean("auto", false).apply()
        PhotoJob.cancel(this)
        msg.text = "Auto-send is off"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            if (hasMediaPerm()) enableAuto() else {
                autoSwitch.isChecked = false
                msg.text = "Photo access is needed for auto-send"
            }
        }
    }

    private fun onCastButton() {
        if (CastState.running) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            return
        }
        val ip = cleanIp()
        if (ip.isEmpty()) { msg.text = "Enter the frame's IP address"; return }
        pendingMode = modes[(castGroup.checkedRadioButtonId - 100).coerceIn(0, 2)]
        pendingUrl = "ws://" + (if (ip.contains(":")) ip else "$ip:81")
        FrameApi.prefs(this).edit()
            .putString("ip", ip).putString("mode", pendingMode)
            .putInt("q", quality).putInt("fps", fps).apply()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("framework API, fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CastService::class.java)
                    .setAction(CastService.ACTION_START)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                    .putExtra("url", pendingUrl)
                    .putExtra("mode", pendingMode)
                    .putExtra("quality", quality)
                    .putExtra("fps", fps)
                startForegroundService(i)
            } else {
                CastState.status = "Screen capture permission denied"
            }
        } else if (requestCode == 5 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            msg.text = "Sending ${uris.size} photo(s)…"
            val app = applicationContext
            Thread {
                val s = PhotoSender.send(app, uris)
                runOnUiThread { msg.text = s }
            }.start()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        super.onDestroy()
    }
}
