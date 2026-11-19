package com.pendo.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs
import kotlin.math.sqrt

class GuardService : Service(), SensorEventListener {

    companion object {
        var running = false
    }

    private val threshold = 4.0          // lower = more sensitive
    private val sirenDelayMs = 10000L    // time to disarm before siren
    private var sm: SensorManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var siren: AudioTrack? = null
    private var armed = false
    private var pending = false
    private var last = -1.0
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(text: String): Notification {
        return Notification.Builder(this, "guard")
            .setContentTitle("Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "Guard", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, note("Arming in 10 seconds"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, note("Arming in 10 seconds"))
        }

        if (running) return START_STICKY
        running = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guard:wake")
        wl?.acquire()

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc != null) {
            sm?.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        }

        handler.postDelayed({
            armed = true
            last = -1.0
            nm.notify(1, note("ARMED"))
        }, 10000)

        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!armed) return
        val x = e.values[0].toDouble()
        val y = e.values[1].toDouble()
        val z = e.values[2].toDouble()
        val m = sqrt(x * x + y * y + z * z)
        if (last >= 0 && abs(m - last) > threshold) onShake()
        last = m
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onShake() {
        if (pending || siren != null) return
        pending = true
        getSystemService(NotificationManager::class.java)
            .notify(1, note("Shake detected! Siren in 10 seconds"))
        handler.postDelayed({
            pending = false
            sound()
        }, sirenDelayMs)
    }

    private fun buildSiren(): AudioTrack {
        val rate = 22050
        val n = (rate * 1.6).toInt()
        val data = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val tri = if (t < 0.5) t * 2 else (1 - t) * 2
            val f = 700 + 1100 * tri
            phase += 2 * Math.PI * f / rate
            val s = if (Math.sin(phase) >= 0) 1.0 else -1.0
            data[i] = (s * 30000).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, n)
        track.setLoopPoints(0, n, -1)
        return track
    }

    private fun sound() {
        if (siren != null) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            val t = buildSiren()
            t.play()
            siren = t
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))
        } catch (e: Exception) {
        }
    }

    private fun cleanup() {
        running = false
        armed = false
        pending = false
        handler.removeCallbacksAndMessages(null)
        sm?.unregisterListener(this)
        siren?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        siren = null
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        } catch (e: Exception) {}
        if (wl?.isHeld == true) wl?.release()
        wl = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
