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
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class ADSBMonitorService : Service() {

    companion object {
        private const val TAG = "ADSBMonitor"
        private const val CHANNEL_ID = "adsb_monitor_channel_v2"
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

    private var gpxLogger: GpxLogger? = null

    private val packetCount = mutableMapOf(
        "heartbeat" to 0,
        "gps" to 0,
        "traffic" to 0,
        "ahrs" to 0,
        "uplink" to 0
    )

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

            val buffer = ByteArray(4096)

            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketIn?.receive(packet)
                    val bytes = packet.data.copyOf(packet.length)
                    processDatagram(bytes)
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

    private fun processDatagram(datagram: ByteArray) {
        val frames = extractGdl90Frames(datagram)
        if (frames.isEmpty()) return

        for (frame in frames) {
            processFrame(frame)
        }
    }

    private var hasLoggedFirstOwnship = false


    private fun processFrame(framePayload: ByteArray) {
        if (framePayload.isEmpty()) return

        val type = framePayload[0].toInt() and 0xFF

        val logicalPacket = ByteArray(framePayload.size + 2)
        logicalPacket[0] = 0x7E.toByte()
        System.arraycopy(framePayload, 0, logicalPacket, 1, framePayload.size)
        logicalPacket[logicalPacket.size - 1] = 0x7E.toByte()

        when (type) {
            0 -> {
                recordPacket("heartbeat")
                // Ignore logging until first valid ownship trkpt has been written
            }

            10 -> {
                recordPacket("gps")

                when (val result = gpxLogger?.writeOwnshipIfPossible(logicalPacket)) {
                    OwnshipWriteResult.WRITTEN -> {
                        hasLoggedFirstOwnship = true
                    }

                    OwnshipWriteResult.REJECTED_TOO_SHORT,
                    OwnshipWriteResult.REJECTED_INVALID_LATLON,
                    OwnshipWriteResult.LOGGER_CLOSED -> {
                        Log.w(TAG, "Bad GPS ownship packet: ${result.name}")
                    }

                    null -> {
                        Log.w(TAG, "gps frame received but gpxLogger is null")
                    }
                }
            }

            11 -> {
                if (hasLoggedFirstOwnship) {
                    gpxLogger?.writeOwnshipGeoAltitudeEvent(logicalPacket)
                }
            }

            20 -> {
                recordPacket("traffic")
                if (hasLoggedFirstOwnship) {
                    gpxLogger?.writeTrafficEvent(logicalPacket)
                }
            }

            7 -> {
                recordPacket("uplink")
                if (hasLoggedFirstOwnship) {
                    gpxLogger?.writeUplinkEvent(logicalPacket)
                }
            }

            0x4C -> {
                recordPacket("ahrs")
                // UI only, no GPX write
            }

            83, 101, 204 -> {
                // Known vendor / status frames; ignore silently
            }

            else -> {
                Log.d(
                    TAG,
                    "Unknown frame type=$type payloadLen=${framePayload.size} hex=${framePayload.toHexString()}"
                )
            }
        }
    }

    private fun extractGdl90Frames(datagram: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var inFrame = false
        var frameBuffer = ByteArrayOutputStream()

        for (b in datagram) {
            val ub = b.toInt() and 0xFF

            if (ub == 0x7E) {
                if (inFrame) {
                    val rawFrame = frameBuffer.toByteArray()
                    if (rawFrame.isNotEmpty()) {
                        val deescaped = deescapeGdl90(rawFrame)
                        if (deescaped.isNotEmpty()) {
                            frames.add(deescaped)
                        }
                    }
                    frameBuffer.reset()
                } else {
                    inFrame = true
                    frameBuffer.reset()
                }
            } else if (inFrame) {
                frameBuffer.write(ub)
            }
        }

        return frames
    }

    private fun deescapeGdl90(rawFrame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0

        while (i < rawFrame.size) {
            val b = rawFrame[i].toInt() and 0xFF
            if (b == 0x7D) {
                if (i + 1 < rawFrame.size) {
                    val next = rawFrame[i + 1].toInt() and 0xFF
                    out.write(next xor 0x20)
                    i += 2
                } else {
                    Log.w(TAG, "Dangling GDL90 escape byte at end of frame")
                    break
                }
            } else {
                out.write(b)
                i++
            }
        }

        return out.toByteArray()
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
                hasLoggedFirstOwnship = false
                Log.d(TAG, "Logging started: ${gpxLogger?.getLocationDescription()}")
            }
        } else {
            gpxLogger?.close()
            gpxLogger = null
            hasLoggedFirstOwnship = false
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
            val packet = DatagramPacket(sendData, sendData.size, target, STRATUS_PORT)
            outSocket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "sendModePacket failed: ${e::class.java.name}: $e", e)
            broadcastError("Unable to send Stratus mode packet: ${e::class.java.simpleName}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADS-B Monitor Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Foreground service status for ADS-B monitoring and GPX logging"
                enableVibration(false)
                setSound(null, null)   // remove this line if you want an audible sound
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent =
            if (openIntent != null) {
                android.app.PendingIntent.getActivity(
                    this,
                    1,
                    openIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

        val stopIntent = Intent(this, ADSBMonitorService::class.java).apply {
            action = ADSBActions.ACTION_STOP
        }

        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            2,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ADS-B Monitor active")
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "ADS-B Monitor active\n$text\nReceiving GDL-90 on UDP 4000 and writing GPX logs."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)

        openPendingIntent?.let {
            builder.setContentIntent(it)
        }

        return builder.build()
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

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}