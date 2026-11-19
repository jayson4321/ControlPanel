package com.example.controlpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

private val MP = ViewGroup.LayoutParams.MATCH_PARENT
private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private val FLAGS = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

object Sh {
    private val newProc by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun exec(cmd: String): String = try {
        val p = newProc.invoke(null, arrayOf("sh", "-c", "( $cmd ) 2>/dev/null"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { "" }
}

class BigSlider(
    ctx: Context, private val icon: String, private val maxV: Int, start: Int,
    private val onChange: (Int, Boolean) -> Unit
) : View(ctx) {
    var value: Int = start
        set(v) { field = v.coerceIn(0, maxV); invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF48484A.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.resources.displayMetrics.density * 20 }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        c.drawRoundRect(0f, 0f, w, h, r, r, bg)
        c.save()
        val clip = Path()
        clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(0f, 0f, w * value / maxV, h, fg)
        c.restore()
        c.drawText(icon, h * 0.3f, h / 2 + tp.textSize / 3, tp)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        value = (e.x / width * maxV).toInt()
        val done = e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL
        onChange(value, done)
        return true
    }
}

class PanelService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var strip: View? = null
    private var panel: FrameLayout? = null
    private var content: View? = null
    private var brightness: BigSlider? = null
    private val circles = mutableMapOf<String, TextView>()
    private val states = mutableMapOf<String, Boolean>()
    private var torchOn = false
    private var lastB = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("panel", "Control panel", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "panel")
            .setContentTitle("Control panel is running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true).build())
        try {
            (getSystemService(CAMERA_SERVICE) as CameraManager).registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(id: String, enabled: Boolean) {
                        torchOn = enabled
                        circles["torch"]?.background = circleBg(enabled)
                    }
                }, ui)
        } catch (e: Throwable) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (Settings.canDrawOverlays(this)) addStrip()
        else toast("Allow 'display over other apps' first, then stop and start again")
        return START_STICKY
    }

    override fun onDestroy() {
        try { strip?.let { wm.removeView(it) } } catch (e: Throwable) {}
        try { panel?.let { wm.removeView(it) } } catch (e: Throwable) {}
        super.onDestroy()
    }

    private fun addStrip() {
        if (strip != null) return
        val v = View(this)
        v.setBackgroundColor(0x55FF0000)
        var downY = 0f
        var fired = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - downY > dp(24)) {
                    fired = true
                    showPanel()
                }
            }
            true
        }
        val lp = WLP(MP, dp(120), WLP.TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP
        wm.addView(v, lp)
        strip = v
    }

    private fun showPanel() {
        if (panel != null) return
        val root = buildPanel()
        panel = root
        wm.addView(root, WLP(MP, MP, WLP.TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT))
        circles["torch"]?.background = circleBg(torchOn)
        root.alpha = 0f
        content?.translationY = -dp(60).toFloat()
        root.animate().alpha(1f).setDuration(180).start()
        content?.animate()?.translationY(0f)?.setDuration(220)?.start()
        refreshStates()
    }

    private fun hidePanel() {
        val p = panel ?: return
        panel = null
        p.animate().alpha(0f).setDuration(150).withEndAction {
            try { wm.removeView(p) } catch (e: Throwable) {}
        }.start()
    }

    private fun circleBg(on: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (on) 0xFF0A84FF.toInt() else 0xFF48484A.toInt())
    }

    private fun tile(key: String, icon: String, label: String): View {
        val circle = TextView(this)
        circle.text = icon
        circle.textSize = 24f
        circle.gravity = Gravity.CENTER
        circle.background = circleBg(false)
        circle.layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        circle.setOnClickListener { toggle(key) }
        circles[key] = circle
        val lbl = TextView(this)
        lbl.text = label
        lbl.textSize = 11f
        lbl.setTextColor(Color.WHITE)
        lbl.gravity = Gravity.CENTER
        lbl.layoutParams = LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(6) }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        col.addView(circle)
        col.addView(lbl)
        return col
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(12) }
        views.forEach { r.addView(it) }
        return r
    }

    private fun dismissOnSwipeUp(v: View, tapToo: Boolean) {
        var y0 = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> y0 = e.rawY
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - y0
                    if (dy < -dp(40) || (tapToo && Math.abs(dy) < dp(8))) hidePanel()
                }
            }
            true
        }
    }

    private fun buildPanel(): FrameLayout {
        circles.clear()
        val root = FrameLayout(this)
        root.setBackgroundColor(0x99000000.toInt())
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(20), dp(16), dp(20))
        c.background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            setColor(0xEE2C2C2E.toInt())
        }
        c.layoutParams = FrameLayout.LayoutParams(MP, WC).apply {
            setMargins(dp(12), dp(40), dp(12), 0)
        }
        content = c
        c.addView(row(tile("wifi", "📶", "Wi-Fi"), tile("bt", "🔵", "Bluetooth"),
            tile("data", "📡", "Mobile data"), tile("air", "✈️", "Airplane")))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        c.addView(row(tile("torch", "🔦", "Flashlight"), tile("rot", "🔄", "Rotate"),
            tile("dnd", "🌙", "Focus"), spacer))

        val b = BigSlider(this, "☀️", 255, 128) { v, done -> setBrightness(v, done) }
        b.layoutParams = LinearLayout.LayoutParams(MP, dp(56)).apply { topMargin = dp(16) }
        brightness = b
        c.addView(b)

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = BigSlider(this, "🔊", maxVol, am.getStreamVolume(AudioManager.STREAM_MUSIC)) { v, _ ->
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (e: Throwable) {}
        }
        vol.layoutParams = LinearLayout.LayoutParams(MP, dp(56)).apply { topMargin = dp(12) }
        c.addView(vol)

        dismissOnSwipeUp(root, true)
        dismissOnSwipeUp(c, false)
        root.addView(c)
        return root
    }

    private fun setBrightness(v: Int, done: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!done && now - lastB < 150) return
        lastB = now
        if (!Sh.ready()) { if (done) toast("Start Shizuku and allow permission"); return }
        Thread {
            Sh.exec("settings put system screen_brightness_mode 0; " +
                "settings put system screen_brightness ${maxOf(v, 1)}")
        }.start()
    }

    private fun toggle(key: String) {
        if (key == "torch") {
            try {
                val cam = getSystemService(CAMERA_SERVICE) as CameraManager
                torchOn = !torchOn
                cam.setTorchMode(cam.cameraIdList.first(), torchOn)
                circles["torch"]?.background = circleBg(torchOn)
            } catch (e: Throwable) { torchOn = !torchOn; toast("Flashlight unavailable") }
            return
        }
        if (!Sh.ready()) { toast("Start Shizuku and allow permission"); return }
        val on = !(states[key] ?: false)
        states[key] = on
        circles[key]?.background = circleBg(on)
        val cmd = when (key) {
            "wifi" -> "svc wifi " + (if (on) "enable" else "disable")
            "bt" -> "svc bluetooth " + (if (on) "enable" else "disable")
            "data" -> "svc data " + (if (on) "enable" else "disable")
            "air" -> "cmd connectivity airplane-mode " + (if (on) "enable" else "disable")
            "rot" -> "settings put system accelerometer_rotation " + (if (on) 1 else 0)
            "dnd" -> "cmd notification set_dnd " + (if (on) "on" else "off")
            else -> return
        }
        Thread { Sh.exec(cmd) }.start()
    }

    private fun refreshStates() {
        Thread {
            if (!Sh.ready()) {
                ui.post { toast("Start Shizuku and allow permission") }
                return@Thread
            }
            val l = Sh.exec(
                "settings get global wifi_on; settings get global bluetooth_on; " +
                "settings get global mobile_data; settings get global airplane_mode_on; " +
                "settings get system accelerometer_rotation; settings get global zen_mode; " +
                "settings get system screen_brightness"
            ).lines().map { it.trim() }
            ui.post {
                fun on(i: Int): Boolean {
                    val s = l.getOrNull(i)
                    return s != null && s != "" && s != "0" && s != "null"
                }
                val m = mapOf("wifi" to on(0), "bt" to on(1), "data" to on(2),
                    "air" to on(3), "rot" to on(4), "dnd" to on(5))
                m.forEach { (k, v) ->
                    states[k] = v
                    circles[k]?.background = circleBg(v)
                }
                l.getOrNull(6)?.toIntOrNull()?.let { brightness?.value = it }
            }
        }.start()
    }
}
