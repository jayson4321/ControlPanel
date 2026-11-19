package com.example.controlpanel

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        status.textSize = 16f
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(48, 120, 48, 48)
        col.addView(status)
        col.addView(btn("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)))
        })
        col.addView(btn("2. Allow Shizuku") {
            try {
                if (Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                ) Shizuku.requestPermission(1)
            } catch (e: Throwable) {}
        })
        col.addView(btn("3. Start panel") {
            startForegroundService(Intent(this, PanelService::class.java))
        })
        col.addView(btn("Stop panel") {
            stopService(Intent(this, PanelService::class.java))
        })
        setContentView(col)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        }
    }

    private fun btn(t: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { action(); refresh() }
        return b
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sh = try {
            if (!Shizuku.pingBinder()) "not running"
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "OK"
            else "permission needed"
        } catch (e: Throwable) { "not running" }
        val ov = if (Settings.canDrawOverlays(this)) "OK" else "needed"
        status.text = "Overlay permission: $ov\nShizuku: $sh"
    }
}
