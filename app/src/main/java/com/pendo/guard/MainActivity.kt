package com.pendo.guard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val pin = "1234"   // change this
    private lateinit var status: TextView
    private lateinit var pinBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(40, 120, 40, 40)

        status = TextView(this)
        status.textSize = 24f
        status.gravity = Gravity.CENTER

        pinBox = EditText(this)
        pinBox.hint = "PIN"
        pinBox.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        val arm = Button(this)
        arm.text = "ARM (10s delay)"
        arm.setOnClickListener {
            startForegroundService(Intent(this, GuardService::class.java))
            status.text = "Arming in 10 seconds..."
        }

        val disarm = Button(this)
        disarm.text = "DISARM"
        disarm.setOnClickListener {
            if (pinBox.text.toString() == pin) {
                stopService(Intent(this, GuardService::class.java))
                pinBox.setText("")
                status.text = "Disarmed"
            } else {
                status.text = "Wrong PIN"
            }
        }

        val appSettings = Button(this)
        appSettings.text = "App settings (battery, autostart)"
        appSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        box.addView(status)
        box.addView(arm)
        box.addView(pinBox)
        box.addView(disarm)
        box.addView(appSettings)
        setContentView(box)
        refresh()

        if (intent.getBooleanExtra("arm", false)) {
            startForegroundService(Intent(this, GuardService::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = if (GuardService.running) "ARMED" else "Disarmed"
    }
}
