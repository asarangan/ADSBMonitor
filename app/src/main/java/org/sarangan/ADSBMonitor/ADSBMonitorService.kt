package org.sarangan.ADSBMonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class ADSBMonitorService : Service() {

    companion object {
        private const val TAG = "ADSBMonitor"
        private const val CHANNEL_ID = "adsb_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val GDL90_PORT = 4000
        private const val STRATUS_PORT = 41500
    }

    private var broadcastAddress: InetAddress? = null

    private var running = false
    private var openGdlMode = true
    private var loggingEnabled = false

    private var socketOut: DatagramSocket? = null
    private var socketIn: DatagramSocket? = null
    private var workerThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val packetCount = mutableMapOf(
        "heartbeat" to 0,
        "gps" to 0,
        "traffic" to 0,
        "ahrs" to 0,
        "uplink" to 0
    )

    private val rejectedCount = mutableMapOf(
        "ownship_too_short" to 0,
        "ownship_zero_latlon" to 0,
        "ownship_invalid_latlon" to 0,
        "ownship_unreasonable_jump" to 0,
        "logger_closed" to 0
    )

    private var lastGpxWriteTimeMs: Long = 0L

    private var gpxLogger: GpxLogger? = null

    @Volatile
    private var cleanedUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ADSBActions.ACTION_START -> {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Starting"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    else 0
                )

                Log.d(TAG, "Foreground notification started")

                openGdlMode = intent.getBooleanExtra(ADSBExtras.EXTRA_OPEN_GDL, true)
                loggingEnabled = intent.getBooleanExtra(ADSBExtras.EXTRA_LOGGING_ENABLED, false)

                if (!running) {
                    startMonitoring()
                } else {
                    setLogging(loggingEnabled)
                    sendModePacketAsync()
                    broadcastStatus()
                    updateNotification()
                }
            }

            ADSBActions.ACTION_SET_MODE -> {
                openGdlMode = intent.getBooleanExtra(ADSBExtras.EXTRA_OPEN_GDL, true)
                sendModePacketAsync()
                broadcastStatus()
                updateNotification()
            }

            ADSBActions.ACTION_SET_LOGGING -> {
                val enabled = intent.getBooleanExtra(ADSBExtras.EXTRA_LOGGING_ENABLED, false)
                setLogging(enabled)
                broadcastStatus()
                updateNotification()
            }

            ADSBActions.ACTION_STOP -> {
                stopAndCleanup()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAndCleanup()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopAndCleanup()
        super.onDestroy()
    }

    private fun stopAndCleanup() {
        if (cleanedUp) return
        cleanedUp = true

        running = false

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        try {
            socketIn?.close()
        } catch (_: Exception) {
        }

        try {
            socketOut?.close()
        } catch (_: Exception) {
        }

        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }

        Thread {
            try {
                gpxLogger?.close()
            } catch (_: Exception) {
            } finally {
                gpxLogger = null
                stopSelf()
            }
        }.start()
    }

    private fun startMonitoring() {
        running = true
        cleanedUp = false

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        multicastLock = wifiManager.createMulticastLock("adsb_multicast_lock").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            socketOut = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create output socket", e)
            broadcastError("Unable to create UDP output socket")
            stopSelf()
            return
        }

        try {
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null) {
                val bcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val quads = byteArrayOf(
                    (bcast and 0xFF).toByte(),
                    ((bcast shr 8) and 0xFF).toByte(),
                    ((bcast shr 16) and 0xFF).toByte(),
                    ((bcast shr 24) and 0xFF).toByte()
                )
                broadcastAddress = InetAddress.getByAddress(quads)
            } else {
                broadcastAddress = InetAddress.getByName("255.255.255.255")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to compute broadcast address", e)
            try {
                broadcastAddress = InetAddress.getByName("255.255.255.255")
            } catch (_: Exception) {
                broadcastAddress = null
            }
        }

        Log.d(TAG, "broadcastAddress=${broadcastAddress?.hostAddress}")

        setLogging(loggingEnabled)

        workerThread = thread(start = true, name = "adsb-monitor-thread") {
            try {
                socketIn = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2000
                    bind(InetSocketAddress(GDL90_PORT))
                }
                Log.d(TAG, "Listening on UDP $GDL90_PORT with reuseAddress=true")
            } catch (e: Exception) {
                broadcastError("Cannot open port $GDL90_PORT")
                Log.e(TAG, "Unable to open shared port $GDL90_PORT", e)
                stopSelf()
                return@thread
            }

            sendModePacket()
            broadcastStatus()
            updateNotification()

            val buffer = ByteArray(2048)

            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketIn?.receive(packet)
                    val bytes = packet.data.copyOf(packet.length)
                    processPacket(bytes)
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Runtime exception", e)
                        broadcastError("Runtime error")
                    }
                }
            }
        }
    }

    private fun processPacket(packet: ByteArray) {
        if (packet.size < 2) return
        if ((packet[0].toInt() and 0xFF) != 0x7E) return

        val type = packet[1].toInt() and 0xFF

        when (type) {
            0 -> {
                recordPacket("heartbeat")
                logPacketDiagnostics("heartbeat")
            }

            10 -> {
                recordPacket("gps")

                val result = gpxLogger?.writeOwnshipIfPossible(packet)

                when (result) {
                    OwnshipWriteResult.WRITTEN -> {
                        lastGpxWriteTimeMs = System.currentTimeMillis()
                    }

                    OwnshipWriteResult.REJECTED_TOO_SHORT -> {
                        incrementRejected("ownship_too_short")
                    }

                    OwnshipWriteResult.REJECTED_ZERO_LATLON -> {
                        incrementRejected("ownship_zero_latlon")
                    }

                    OwnshipWriteResult.REJECTED_INVALID_LATLON -> {
                        incrementRejected("ownship_invalid_latlon")
                    }

                    OwnshipWriteResult.REJECTED_UNREASONABLE_JUMP -> {
                        incrementRejected("ownship_unreasonable_jump")
                    }

                    OwnshipWriteResult.LOGGER_CLOSED -> {
                        incrementRejected("logger_closed")
                    }

                    null -> {
                        incrementRejected("logger_closed")
                        Log.w(TAG, "gps packet received but gpxLogger is null")
                    }
                }

                logPacketDiagnostics("gps")
            }

            20 -> {
                recordPacket("traffic")
                gpxLogger?.queueTraffic(packet)
                logPacketDiagnostics("traffic")
            }

            7 -> {
                recordPacket("uplink")
                gpxLogger?.queueUplink(packet)
                logPacketDiagnostics("uplink")
            }

            0x4C -> {
                recordPacket("ahrs")
                logPacketDiagnostics("ahrs")
            }

            else -> {
                Log.d(TAG, "pkt=unknown type=$type len=${packet.size}")
            }
        }
    }

    private fun incrementRejected(key: String) {
        rejectedCount[key] = (rejectedCount[key] ?: 0) + 1
    }

    private fun logPacketDiagnostics(packetType: String) {
        val heartbeat = packetCount["heartbeat"] ?: 0
        val gps = packetCount["gps"] ?: 0
        val traffic = packetCount["traffic"] ?: 0
        val ahrs = packetCount["ahrs"] ?: 0
        val uplink = packetCount["uplink"] ?: 0

        val dt = if (lastGpxWriteTimeMs == 0L) {
            -1L
        } else {
            System.currentTimeMillis() - lastGpxWriteTimeMs
        }

        val dtText = if (dt < 0) "n/a" else "${dt} ms"

        Log.d(
            TAG,
            "pkt=$packetType " +
                    "counts: hb=$heartbeat gps=$gps traffic=$traffic ahrs=$ahrs uplink=$uplink " +
                    "sinceLastWrite=$dtText " +
                    "rejected: short=${rejectedCount["ownship_too_short"] ?: 0} " +
                    "zero=${rejectedCount["ownship_zero_latlon"] ?: 0} " +
                    "invalid=${rejectedCount["ownship_invalid_latlon"] ?: 0} " +
                    "jump=${rejectedCount["ownship_unreasonable_jump"] ?: 0} " +
                    "loggerClosed=${rejectedCount["logger_closed"] ?: 0}"
        )
    }

    private fun recordPacket(token: String) {
        val newCount = (packetCount[token] ?: 0) + 1
        packetCount[token] = newCount

        val intent = Intent(ADSBActions.ACTION_PACKET).apply {
            setPackage(packageName)
            putExtra(ADSBExtras.EXTRA_PACKET_TYPE, token)
            putExtra(ADSBExtras.EXTRA_COUNT, newCount)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus() {
        val intent = Intent(ADSBActions.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(ADSBExtras.EXTRA_OPEN_GDL, openGdlMode)
            putExtra(ADSBExtras.EXTRA_LOGGING_ENABLED, loggingEnabled)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(text: String) {
        val intent = Intent(ADSBActions.ACTION_ERROR).apply {
            setPackage(packageName)
            putExtra(ADSBExtras.EXTRA_ERROR_TEXT, text)
        }
        sendBroadcast(intent)
    }

    private fun setLogging(enabled: Boolean) {
        loggingEnabled = enabled
        if (enabled) {
            if (gpxLogger == null) {
                gpxLogger = GpxLogger(this)
                Log.d(TAG, "Logging started: ${gpxLogger?.getLocationDescription()}")
            }
        } else {
            gpxLogger?.close()
            gpxLogger = null
            Log.d(TAG, "Logging stopped")
        }
    }

    private fun sendModePacketAsync() {
        Thread {
            sendModePacket()
        }.start()
    }

    private fun sendModePacket() {
        val sendData = if (openGdlMode) stratusDataOpen else stratusDataClose

        try {
            val outSocket = socketOut ?: DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
                socketOut = this
            }

            val target = broadcastAddress ?: InetAddress.getByName("255.255.255.255")

            Log.d(TAG, "sendModePacket: target=${target.hostAddress} port=$STRATUS_PORT bytes=${sendData.size}")
            Log.d(TAG, "sendModePacket: wifi connected=${isWifiConnected()} broadcast=${outSocket.broadcast}")

            val packet = DatagramPacket(sendData, sendData.size, target, STRATUS_PORT)
            outSocket.send(packet)

            Log.d(TAG, "sendModePacket: success")
        } catch (e: Exception) {
            Log.e(TAG, "sendModePacket failed: ${e::class.java.name}: $e", e)
            broadcastError("Unable to send Stratus mode packet: ${e::class.java.simpleName}")
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADS-B Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ADSBMonitorService::class.java).apply {
            action = ADSBActions.ACTION_STOP
        }

        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            2,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADS-B Monitor Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val text = buildString {
            append(if (openGdlMode) "Open-GDL" else "ForeFlight")
            append(" • ")
            append(if (loggingEnabled) "GPX logging ON" else "GPX logging OFF")
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}