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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ADSBMonitorService : Service() {


    companion object {
        private const val TAG = "ADSBMonitor"
        private const val CHANNEL_ID = "adsb_monitor_channel_v2"
        private const val NOTIFICATION_ID = 1001
        private const val GDL90_PORT = 4000
        private const val STRATUS_PORT = 41500

        private const val STARTUP_MODE_BURST_COUNT = 4
        private const val STARTUP_MODE_BURST_DELAY_MS = 1500L
        private const val MODE_KEEP_ALIVE_INTERVAL_MS = 60_000L

        private const val PACKET_QUALITY_OK = "ok"
        private const val PACKET_QUALITY_WARN = "warn"

        private const val MAX_OWNSHIP_SPEED_KT = 500.0

        private const val MIN_GEO_ALTITUDE_FT = -1500.0
        private const val MAX_GEO_ALTITUDE_FT = 60000.0
        private const val MAX_GEO_VERTICAL_SPEED_FPM = 6000.0

    }

    private data class LastOwnshipFix(
        val timeMillis: Long,
        val latitude: Double,
        val longitude: Double
    )

    private data class LastGeoAltitude(
        val timeMillis: Long,
        val altitudeFeet: Double
    )

    private var lastOwnshipFix: LastOwnshipFix? = null
    private var lastGeoAltitude: LastGeoAltitude? = null


    @Volatile
    private var running = false

    @Volatile
    private var openGdlMode = true

    @Volatile
    private var loggingEnabled = false

    @Volatile
    private var cleanedUp = false

    @Volatile
    private var modeKeepAliveThread: Thread? = null

    private var socketOut: DatagramSocket? = null
    private var socketIn: DatagramSocket? = null
    private var workerThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var gpxLogger: GpxLogger? = null

    private val packetCount = mutableMapOf(
        "heartbeat" to 0,
        "gps" to 0,
        "geoalt" to 0,
        "traffic" to 0,
        "ahrs" to 0,
        "uplink" to 0
    )

    private var hasLoggedFirstOwnship = false

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
                    startModeKeepAlive()
                    broadcastStatus()
                    updateNotification()
                }
            }

            ADSBActions.ACTION_SET_MODE -> {
                openGdlMode = intent.getBooleanExtra(ADSBExtras.EXTRA_OPEN_GDL, true)

                // Send immediately when the switch changes. The keep-alive thread will
                // then continue sending the currently selected mode once per minute.
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
                return START_NOT_STICKY
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
            stopAndCleanup()
            return
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
                stopAndCleanup()
                return@thread
            }

            sendModePacketBurstAsync()
            startModeKeepAlive()
            broadcastStatus()
            updateNotification()

            val buffer = ByteArray(4096)

            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketIn?.receive(packet)
                    val bytes = packet.data.copyOf(packet.length)
                    processDatagram(bytes)
                } catch (_: SocketTimeoutException) {
                    // Normal timeout used so the loop can periodically check running.
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Runtime exception", e)
                        broadcastError("Runtime error")
                    }
                }
            }

            Log.d(TAG, "ADS-B monitor worker thread stopped")
        }
    }

    private fun stopAndCleanup() {
        if (cleanedUp) return
        cleanedUp = true

        running = false

        stopModeKeepAlive()

        try {
            workerThread?.interrupt()
        } catch (_: Exception) {
        }
        workerThread = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        try {
            socketIn?.close()
        } catch (_: Exception) {
        }
        socketIn = null

        try {
            socketOut?.close()
        } catch (_: Exception) {
        }
        socketOut = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null

        thread(start = true, name = "adsb-gpx-close") {
            try {
                gpxLogger?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GPX logger", e)
            } finally {
                gpxLogger = null
                hasLoggedFirstOwnship = false
                stopSelf()
            }
        }
    }

    private fun sendModePacketBurstAsync() {
        thread(start = true, name = "adsb-mode-startup-burst") {
            repeat(STARTUP_MODE_BURST_COUNT) { index ->
                if (!running || Thread.currentThread().isInterrupted) return@thread

                Log.d(TAG, "Sending startup mode packet ${index + 1}/$STARTUP_MODE_BURST_COUNT")
                sendModePacket()

                try {
                    Thread.sleep(STARTUP_MODE_BURST_DELAY_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun startModeKeepAlive() {
        if (modeKeepAliveThread?.isAlive == true) {
            Log.d(TAG, "Mode keep-alive already running")
            return
        }

        modeKeepAliveThread = thread(start = true, name = "adsb-mode-keepalive") {
            Log.d(TAG, "Mode keep-alive thread started")

            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(MODE_KEEP_ALIVE_INTERVAL_MS)

                    if (!running || Thread.currentThread().isInterrupted) break

                    Log.d(
                        TAG,
                        "Sending periodic Stratus mode keep-alive: " +
                                if (openGdlMode) "OPEN/GDL" else "CLOSE/ForeFlight"
                    )

                    sendModePacket()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mode keep-alive thread", e)
                }
            }

            Log.d(TAG, "Mode keep-alive thread stopped")
        }
    }

    private fun stopModeKeepAlive() {
        try {
            modeKeepAliveThread?.interrupt()
        } catch (_: Exception) {
        } finally {
            modeKeepAliveThread = null
        }
    }

    private fun processDatagram(datagram: ByteArray) {
        val frames = extractGdl90Frames(datagram)
        if (frames.isEmpty()) return

        for (frame in frames) {
            processFrame(frame)
        }
    }

    private fun processFrame(framePayload: ByteArray) {
        if (framePayload.isEmpty()) return

        val type = framePayload[0].toInt() and 0xFF

        // GPX stores the logical, de-escaped GDL-90 frame payload only.
        // Do not add leading/trailing 0x7E frame delimiters here.
        val logicalPacket = framePayload.copyOf()

        when (type) {
            0 -> {
                recordPacket("heartbeat")
            }

            10 -> {
                val quality = classifyOwnshipQuality(logicalPacket)
                recordPacket("gps", quality)

                if (quality == PACKET_QUALITY_OK) {
                    when (val result = gpxLogger?.writeOwnshipIfPossible(logicalPacket)) {
                        OwnshipWriteResult.WRITTEN -> {
                            hasLoggedFirstOwnship = true
                        }

                        OwnshipWriteResult.REJECTED_TOO_SHORT,
                        OwnshipWriteResult.REJECTED_INVALID_LATLON,
                        OwnshipWriteResult.LOGGER_CLOSED -> {
                            Log.w(TAG, "Bad GPS ownship packet: ${result.name}")
                            gpxLogger?.writeOwnshipDiagnosticEvent(logicalPacket)
                        }

                        null -> {
                            Log.w(TAG, "gps frame received but gpxLogger is null")
                        }
                    }
                } else {
                    Log.w(TAG, "Ownship packet received but marked amber/warning")
                    gpxLogger?.writeOwnshipDiagnosticEvent(logicalPacket)
                }
            }

            11 -> {
                val quality = classifyGeoAltitudeQuality(logicalPacket)
                recordPacket("geoalt", quality)

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
            }

            0x65 -> {
                recordPacket("ahrs")
            }

            83, 101, 204 -> {
                // Known vendor / status frames; ignore silently.
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
        val frameBuffer = ByteArrayOutputStream()

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

    private fun recordPacket(
        token: String,
        quality: String = PACKET_QUALITY_OK
    ) {
        val newCount = (packetCount[token] ?: 0) + 1
        packetCount[token] = newCount

        val intent = Intent(ADSBActions.ACTION_PACKET).apply {
            setPackage(packageName)
            putExtra(ADSBExtras.EXTRA_PACKET_TYPE, token)
            putExtra(ADSBExtras.EXTRA_COUNT, newCount)
            putExtra(ADSBExtras.EXTRA_PACKET_QUALITY, quality)
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
        thread(start = true, name = "adsb-mode-send") {
            sendModePacket()
        }
    }

    private fun getDirectedBroadcastAddress(): InetAddress? {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val ip = wifiManager.connectionInfo.ipAddress

            if (ip == 0) {
                Log.w(TAG, "WiFi IP address is 0; cannot derive directed broadcast")
                return null
            }

            // Android reports WifiInfo.ipAddress little-endian on typical devices.
            val a = ip and 0xFF
            val b = ip shr 8 and 0xFF
            val c = ip shr 16 and 0xFF

            val broadcastString = "$a.$b.$c.255"

            Log.d(TAG, "Derived directed broadcast address: $broadcastString")

            InetAddress.getByName(broadcastString)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to derive directed broadcast address", e)
            null
        }
    }

    private fun sendModePacket() {
        val sendData = if (openGdlMode) stratusDataOpen else stratusDataClose

        try {
            val outSocket = socketOut ?: DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
                socketOut = this
            }

            val target = getDirectedBroadcastAddress()
                ?: InetAddress.getByName("255.255.255.255")

            Log.d(
                TAG,
                "Sending mode packet ${if (openGdlMode) "OPEN/GDL" else "CLOSE/ForeFlight"} " +
                        "to ${target.hostAddress}:$STRATUS_PORT"
            )

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
                setSound(null, null)
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


    private fun readSigned24(packet: ByteArray, offset: Int): Int {
        var value =
            ((packet[offset].toInt() and 0xFF) shl 16) or
                    ((packet[offset + 1].toInt() and 0xFF) shl 8) or
                    (packet[offset + 2].toInt() and 0xFF)

        if ((value and 0x800000) != 0) {
            value = value or -0x1000000
        }

        return value
    }

    private fun haversineNm(
        lat1Deg: Double,
        lon1Deg: Double,
        lat2Deg: Double,
        lon2Deg: Double
    ): Double {
        val earthRadiusNm = 3440.065

        val lat1 = Math.toRadians(lat1Deg)
        val lon1 = Math.toRadians(lon1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val lon2 = Math.toRadians(lon2Deg)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a =
            kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                    kotlin.math.cos(lat1) *
                    kotlin.math.cos(lat2) *
                    kotlin.math.sin(dLon / 2) *
                    kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadiusNm * c
    }

    private fun classifyOwnshipQuality(packet: ByteArray): String {
        if (packet.size < 11) return PACKET_QUALITY_WARN

        val type = packet[0].toInt() and 0xFF
        if (type != 0x0A) return PACKET_QUALITY_WARN

        val latRaw = readSigned24(packet, 5)
        val lonRaw = readSigned24(packet, 8)

        if (latRaw == 0 && lonRaw == 0) {
            Log.w(TAG, "Ownship GPS warning: zero lat/lon from receiver")
            return PACKET_QUALITY_WARN
        }

        val latitude = latRaw * 180.0 / 8388608.0
        val longitude = lonRaw * 180.0 / 8388608.0

        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            Log.w(TAG, "Ownship GPS warning: invalid lat/lon $latitude, $longitude")
            return PACKET_QUALITY_WARN
        }

        val now = System.currentTimeMillis()
        val previous = lastOwnshipFix

        if (previous != null) {
            val dtHours = (now - previous.timeMillis) / 3_600_000.0

            if (dtHours > 0.0001) {
                val distanceNm = haversineNm(
                    previous.latitude,
                    previous.longitude,
                    latitude,
                    longitude
                )

                val speedKt = distanceNm / dtHours

                if (speedKt > MAX_OWNSHIP_SPEED_KT) {
                    Log.w(
                        TAG,
                        "Ownship GPS warning: jump implies ${"%.1f".format(speedKt)} kt"
                    )
                    return PACKET_QUALITY_WARN
                }
            }
        }

        lastOwnshipFix = LastOwnshipFix(
            timeMillis = now,
            latitude = latitude,
            longitude = longitude
        )

        return PACKET_QUALITY_OK
    }



    private fun classifyGeoAltitudeQuality(packet: ByteArray): String {
        if (packet.size < 3) return PACKET_QUALITY_WARN

        val type = packet[0].toInt() and 0xFF
        if (type != 0x0B) return PACKET_QUALITY_WARN

        val raw =
            ((packet[1].toInt() and 0xFF) shl 8) or
                    (packet[2].toInt() and 0xFF)

        val signedRaw = if ((raw and 0x8000) != 0) {
            raw or -0x10000
        } else {
            raw
        }

        val altitudeFeet = signedRaw * 5.0

        if (altitudeFeet !in MIN_GEO_ALTITUDE_FT..MAX_GEO_ALTITUDE_FT) {
            Log.w(TAG, "Geo altitude warning: altitudeFeet=$altitudeFeet")
            return PACKET_QUALITY_WARN
        }

        val now = System.currentTimeMillis()
        val previous = lastGeoAltitude

        if (previous != null) {
            val dtMinutes = (now - previous.timeMillis) / 60_000.0

            if (dtMinutes > 0.001) {
                val verticalSpeedFpm =
                    kotlin.math.abs(altitudeFeet - previous.altitudeFeet) / dtMinutes

                if (verticalSpeedFpm > MAX_GEO_VERTICAL_SPEED_FPM) {
                    Log.w(
                        TAG,
                        "Geo altitude warning: jump implies ${"%.0f".format(verticalSpeedFpm)} fpm"
                    )
                    return PACKET_QUALITY_WARN
                }
            }
        }

        lastGeoAltitude = LastGeoAltitude(
            timeMillis = now,
            altitudeFeet = altitudeFeet
        )

        return PACKET_QUALITY_OK
    }


}
