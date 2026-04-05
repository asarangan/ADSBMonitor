package org.sarangan.ADSBMonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Timer
import java.util.TimerTask

class MainActivity : ComponentActivity() {

    private lateinit var modeSwitch: Switch

    private var serviceRunning = false
    private var suppressCallback = false
    private var autoStartDone = false

    // logging permanently enabled
    private val loggingEnabled = true

    private val timers = mutableMapOf<String, Timer>()

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (!granted) {
                findViewById<TextView>(R.id.textViewError).text =
                    "Notification permission denied"
                return@registerForActivityResult
            }

            findViewById<TextView>(R.id.textViewError).text = ""

            window.decorView.post {
                ensureServiceRunning()
                updateServiceSettings()
            }
        }

    private val adsbReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    ADSBActions.ACTION_PACKET -> {

                        val type =
                            intent.getStringExtra(
                                ADSBExtras.EXTRA_PACKET_TYPE
                            ) ?: return

                        val count =
                            intent.getIntExtra(
                                ADSBExtras.EXTRA_COUNT,
                                0
                            )

                        updatePacketUi(
                            type,
                            count
                        )
                    }

                    ADSBActions.ACTION_STATUS -> {

                        val openGdl =
                            intent.getBooleanExtra(
                                ADSBExtras.EXTRA_OPEN_GDL,
                                true
                            )

                        suppressCallback = true

                        try {

                            modeSwitch.isChecked =
                                openGdl

                        } finally {

                            suppressCallback = false
                        }

                        serviceRunning = true
                    }

                    ADSBActions.ACTION_ERROR -> {

                        val text =
                            intent.getStringExtra(
                                ADSBExtras.EXTRA_ERROR_TEXT
                            ) ?: ""

                        findViewById<TextView>(
                            R.id.textViewError
                        ).text = text
                    }
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout)

        modeSwitch =
            findViewById(R.id.switchGDL90)

        modeSwitch.thumbTintList =
            ColorStateList(
                arrayOf(
                    intArrayOf(
                        android.R.attr.state_checked
                    ),
                    intArrayOf()
                ),
                intArrayOf(
                    Color.GREEN,
                    Color.RED
                )
            )

        // request notification permission (Android 13+)

        if (!hasNotificationPermission()) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // auto-enable GDL90

        suppressCallback = true

        try {

            modeSwitch.isChecked = true

        } finally {

            suppressCallback = false
        }

        setupModeSwitch()
    }

    override fun onStart() {

        super.onStart()

        val filter =
            IntentFilter().apply {

                addAction(
                    ADSBActions.ACTION_PACKET
                )

                addAction(
                    ADSBActions.ACTION_STATUS
                )

                addAction(
                    ADSBActions.ACTION_ERROR
                )
            }

        ContextCompat.registerReceiver(
            this,
            adsbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPostResume() {

        super.onPostResume()

        if (!autoStartDone) {

            autoStartDone = true

            window.decorView.post {

                if (hasNotificationPermission()) {
                    ensureServiceRunning()
                    updateServiceSettings()
                } else {
                    findViewById<TextView>(R.id.textViewError).text =
                        "Notification permission required to start logging service"
                }
            }
        }
    }

    override fun onStop() {

        try {

            unregisterReceiver(
                adsbReceiver
            )

        } catch (_: Exception) {
        }

        super.onStop()
    }

    private fun hasNotificationPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun setupModeSwitch() {

        modeSwitch.setOnCheckedChangeListener { _, _ ->

            if (suppressCallback)
                return@setOnCheckedChangeListener

            if (!hasNotificationPermission()) {

                findViewById<TextView>(R.id.textViewError).text =
                    "Notification permission required to start logging service"

                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )

                return@setOnCheckedChangeListener
            }

            ensureServiceRunning()

            updateServiceSettings()
        }
    }

    private fun ensureServiceRunning() {

        if (serviceRunning)
            return

        val intent =
            Intent(
                this,
                ADSBMonitorService::class.java
            ).apply {

                action =
                    ADSBActions.ACTION_START

                putExtra(
                    ADSBExtras.EXTRA_OPEN_GDL,
                    modeSwitch.isChecked
                )

                putExtra(
                    ADSBExtras.EXTRA_LOGGING_ENABLED,
                    loggingEnabled
                )
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )

        serviceRunning = true
    }

    private fun updateServiceSettings() {

        val modeIntent =
            Intent(
                this,
                ADSBMonitorService::class.java
            ).apply {

                action =
                    ADSBActions.ACTION_SET_MODE

                putExtra(
                    ADSBExtras.EXTRA_OPEN_GDL,
                    modeSwitch.isChecked
                )
            }

        startService(modeIntent)

        val logIntent =
            Intent(
                this,
                ADSBMonitorService::class.java
            ).apply {

                action =
                    ADSBActions.ACTION_SET_LOGGING

                putExtra(
                    ADSBExtras.EXTRA_LOGGING_ENABLED,
                    loggingEnabled
                )
            }

        startService(logIntent)
    }

    private fun updatePacketUi(
        token: String,
        count: Int
    ) {

        val textId: Int
        val lightId: Int

        when (token) {

            "heartbeat" -> {

                textId =
                    R.id.textViewHeartbeatCount

                lightId =
                    R.id.heartbeat_light
            }

            "gps" -> {

                textId =
                    R.id.textViewGPSCount

                lightId =
                    R.id.gps_light
            }

            "geoalt" -> {

                textId =
                    R.id.textViewGeoAltCount

                lightId =
                    R.id.geoalt_light
            }

            "traffic" -> {

                textId =
                    R.id.textViewTrafficCount

                lightId =
                    R.id.traffic_light
            }

            "ahrs" -> {

                textId =
                    R.id.textViewAHRSCount

                lightId =
                    R.id.ahrs_light
            }

            "uplink" -> {

                textId =
                    R.id.textViewUplinkCount

                lightId =
                    R.id.uplink_light
            }

            else -> return
        }

        findViewById<TextView>(
            textId
        ).text =
            count.toString()

        findViewById<Button>(
            lightId
        ).setBackgroundResource(
            R.drawable.circle_green
        )

        timers[token]?.cancel()

        timers[token] =
            Timer().apply {

                schedule(

                    object : TimerTask() {

                        override fun run() {

                            runOnUiThread {

                                findViewById<Button>(
                                    lightId
                                ).setBackgroundResource(
                                    R.drawable.circle_red
                                )
                            }
                        }
                    },

                    2000
                )
            }
    }
}