package org.sarangan.adsbmonitor

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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import androidx.core.app.ServiceCompat

class ADSBMonitorService : Service() {

    private var broadcastAddress: InetAddress? = null

    private var running = false
    private var openGdlMode = true
    private var loggingEnabled = false

    private var socketOut: DatagramSocket? = null
    private var socketIn: MulticastSocket? = null
    private var workerThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val packetCount = mutableMapOf(
        "heartbeat" to 0,
        "gps" to 0,
        "traffic" to 0,
        "ahrs" to 0,
        "uplink" to 0
    )

    private var gpxLogger: GpxLogger? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        //startForeground(NOTIFICATION_ID, buildNotification("Starting"))
    }

    private fun sendModePacketAsync() {
        Thread {
            sendModePacket()
        }.start()
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

                openGdlMode = intent.getBooleanExtra(AdsbExtras.EXTRA_OPEN_GDL, true)
                loggingEnabled = intent.getBooleanExtra(AdsbExtras.EXTRA_LOGGING_ENABLED, false)

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
                openGdlMode = intent.getBooleanExtra(AdsbExtras.EXTRA_OPEN_GDL, true)
                sendModePacketAsync()
                broadcastStatus()
                updateNotification()
            }

            ADSBActions.ACTION_SET_LOGGING -> {
                val enabled = intent.getBooleanExtra(AdsbExtras.EXTRA_LOGGING_ENABLED, false)
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
        // User swiped the app away from Recents.
        stopAndCleanup()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopAndCleanup()
        super.onDestroy()
    }

    @Volatile
    private var cleanedUp = false

    private fun stopAndCleanup() {
        if (cleanedUp) return
        cleanedUp = true

        running = false

        // Stop foreground notification and service
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        // Close sockets / locks
        try { socketIn?.close() } catch (_: Exception) {}
        try { socketOut?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}

        // Close GPX file on a background thread because file I/O is blocking
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

        Log.d(TAG, "broadcastAddress=${broadcastAddress?.hostAddress}")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("adsb_multicast_lock").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            socketOut = DatagramSocket().apply {
                broadcast = true
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
            broadcastAddress = InetAddress.getByName("255.255.255.255")
        }

        setLogging(loggingEnabled)

        workerThread = thread(start = true, name = "adsb-monitor-thread") {
            try {
                socketIn = MulticastSocket(4000).apply {
                    soTimeout = 2000
                }
            } catch (e: Exception) {
                broadcastError("Cannot open port 4000")
                Log.e(TAG, "Unable to open port 4000", e)
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
                    Log.e(TAG, "Runtime exception", e)
                    broadcastError("Runtime error")
                }
            }
        }
    }

    private fun stopMonitoring() {
        running = false

        try { socketIn?.close() } catch (_: Exception) {}
        try { socketOut?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}

        gpxLogger?.close()
        gpxLogger = null
    }

    private fun processPacket(packet: ByteArray) {
        if (packet.size < 2) return
        if ((packet[0].toInt() and 0xFF) != 0x7E) return

        val type = packet[1].toInt() and 0xFF

        when {
            type == 0 -> recordPacket("heartbeat")
            type == 10 -> {
                recordPacket("gps")
                gpxLogger?.writeOwnshipIfPossible(packet)
            }
            type == 20 -> {
                recordPacket("traffic")
                gpxLogger?.queueTraffic(packet)
            }
            type == 7 -> {
                recordPacket("uplink")
                gpxLogger?.queueWeather(packet)
            }
            type == 0x4C -> {
                recordPacket("ahrs")
                // AHRS intentionally not logged
            }
        }
    }

    private fun recordPacket(token: String) {
        val newCount = (packetCount[token] ?: 0) + 1
        packetCount[token] = newCount

        val intent = Intent(ADSBActions.ACTION_PACKET).apply {
            setPackage(packageName)
            putExtra(AdsbExtras.EXTRA_PACKET_TYPE, token)
            putExtra(AdsbExtras.EXTRA_COUNT, newCount)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus() {
        val intent = Intent(ADSBActions.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(AdsbExtras.EXTRA_OPEN_GDL, openGdlMode)
            putExtra(AdsbExtras.EXTRA_LOGGING_ENABLED, loggingEnabled)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(text: String) {
        val intent = Intent(ADSBActions.ACTION_ERROR).apply {
            setPackage(packageName)
            putExtra(AdsbExtras.EXTRA_ERROR_TEXT, text)
        }
        sendBroadcast(intent)
    }

    private fun setLogging(enabled: Boolean) {
        loggingEnabled = enabled
        if (enabled) {
            if (gpxLogger == null) gpxLogger = GpxLogger(this)
        } else {
            gpxLogger?.close()
            gpxLogger = null
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

            val target = broadcastAddress ?: InetAddress.getByName("255.255.255.255")

            Log.d(TAG, "sendModePacket: target=${target.hostAddress} port=41500 bytes=${sendData.size}")
            Log.d(TAG, "sendModePacket: wifi connected=${isWifiConnected()} broadcast=${outSocket.broadcast}")

            val packet = DatagramPacket(sendData, sendData.size, target, 41500)
            outSocket.send(packet)

            Log.d(TAG, "sendModePacket: success")
        } catch (e: Exception) {
            Log.e(TAG, "sendModePacket failed: ${e::class.java.name}: ${e}", e)
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
            .setContentTitle("ADSB Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
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

    companion object {
        private const val CHANNEL_ID = "adsb_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }
}