package org.sarangan.ADSBMonitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OwnshipFix(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double
)

enum class OwnshipWriteResult {
    WRITTEN,
    REJECTED_TOO_SHORT,
    REJECTED_ZERO_LATLON,
    REJECTED_INVALID_LATLON,
    REJECTED_UNREASONABLE_JUMP,
    REJECTED_STARTUP_UNSTABLE,
    LOGGER_CLOSED
}

sealed class OwnshipDecodeResult {
    data class Success(val fix: OwnshipFix) : OwnshipDecodeResult()
    data object TooShort : OwnshipDecodeResult()
    data object ZeroLatLon : OwnshipDecodeResult()
    data object InvalidLatLon : OwnshipDecodeResult()
}

class GpxLogger(private val context: Context) {

    companion object {
        private const val TAG = "ADSBMonitor"

        // Normal running spike filters
        private const val MAX_REASONABLE_SPEED_KTS = 400.0
        private const val MAX_REASONABLE_STEP_METERS = 2000.0

        // Startup stabilization
        private const val STARTUP_REQUIRED_CLUSTER_POINTS = 3
        private const val STARTUP_CLUSTER_RADIUS_METERS = 200.0
        private const val STARTUP_BUFFER_MAX_POINTS = 6
    }

    private val trafficQueue = ConcurrentLinkedQueue<String>()
    private val uplinkQueue = ConcurrentLinkedQueue<String>()

    private var outputStream: OutputStream? = null
    private var uri: Uri? = null
    private var absolutePath: String? = null
    private var closed = false

    private var lastAcceptedFix: OwnshipFix? = null
    private val startupBuffer = ArrayDeque<OwnshipFix>()

    init {
        createOutput()
        write(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"
                 creator="ADSBMonitor"
                 xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:adsb="https://org.sarangan.ADSBMonitor/adsb">
              <trk>
                <name>ADS-B Log</name>
                <trkseg>
            """.trimIndent() + "\n"
        )

        Log.d(TAG, "GPX log file created: ${getLocationDescription()}")
    }

    fun queueTraffic(packet: ByteArray) {
        if (closed) return
        trafficQueue.add(packet.toHexString())
    }

    fun queueUplink(packet: ByteArray) {
        if (closed) return
        uplinkQueue.add(packet.toHexString())
    }

    fun writeOwnshipIfPossible(packet: ByteArray): OwnshipWriteResult {
        if (closed) {
            Log.w(TAG, "writeOwnshipIfPossible called while logger is closed")
            return OwnshipWriteResult.LOGGER_CLOSED
        }

        return when (val decoded = decodeOwnship(packet)) {
            is OwnshipDecodeResult.Success -> {
                val fix = decoded.fix

                // Startup stabilization phase: do not trust the first point blindly.
                if (lastAcceptedFix == null) {
                    val startupResult = handleStartupFix(fix)
                    if (startupResult != OwnshipWriteResult.REJECTED_STARTUP_UNSTABLE) {
                        return startupResult
                    }
                    return OwnshipWriteResult.REJECTED_STARTUP_UNSTABLE
                }

                if (!isReasonableFix(fix)) {
                    Log.w(
                        TAG,
                        "Rejected ownship packet: unreasonable jump to lat=${fix.latitude}, lon=${fix.longitude}"
                    )
                    OwnshipWriteResult.REJECTED_UNREASONABLE_JUMP
                } else {
                    writeOwnship(fix)
                    lastAcceptedFix = fix
                    OwnshipWriteResult.WRITTEN
                }
            }

            OwnshipDecodeResult.TooShort -> {
                Log.w(TAG, "Rejected ownship packet: too short len=${packet.size}")
                OwnshipWriteResult.REJECTED_TOO_SHORT
            }

            OwnshipDecodeResult.ZeroLatLon -> {
                Log.w(TAG, "Rejected ownship packet: zero lat/lon")
                OwnshipWriteResult.REJECTED_ZERO_LATLON
            }

            OwnshipDecodeResult.InvalidLatLon -> {
                Log.w(TAG, "Rejected ownship packet: invalid lat/lon")
                OwnshipWriteResult.REJECTED_INVALID_LATLON
            }
        }
    }

    fun getLocationDescription(): String {
        absolutePath?.let { return it }
        uri?.let { return it.toString() }
        return "unknown"
    }

    private fun handleStartupFix(fix: OwnshipFix): OwnshipWriteResult {
        startupBuffer.addLast(fix)
        while (startupBuffer.size > STARTUP_BUFFER_MAX_POINTS) {
            startupBuffer.removeFirst()
        }

        val anchor = findStartupAnchor()
        return if (anchor != null) {
            Log.d(
                TAG,
                "Startup stabilized with ${startupBuffer.size} buffered points; anchor lat=${anchor.latitude}, lon=${anchor.longitude}"
            )
            writeOwnship(anchor)
            lastAcceptedFix = anchor
            startupBuffer.clear()
            OwnshipWriteResult.WRITTEN
        } else {
            Log.d(
                TAG,
                "Startup not yet stable: buffered=${startupBuffer.size}, waiting for $STARTUP_REQUIRED_CLUSTER_POINTS points within ${STARTUP_CLUSTER_RADIUS_METERS} m"
            )
            OwnshipWriteResult.REJECTED_STARTUP_UNSTABLE
        }
    }

    private fun findStartupAnchor(): OwnshipFix? {
        if (startupBuffer.size < STARTUP_REQUIRED_CLUSTER_POINTS) return null

        val fixes = startupBuffer.toList()

        for (candidate in fixes) {
            var closeCount = 0
            for (other in fixes) {
                val d = haversineMeters(
                    candidate.latitude,
                    candidate.longitude,
                    other.latitude,
                    other.longitude
                )
                if (d <= STARTUP_CLUSTER_RADIUS_METERS) {
                    closeCount++
                }
            }

            if (closeCount >= STARTUP_REQUIRED_CLUSTER_POINTS) {
                // Use the most recent fix in the cluster as the anchor
                for (i in fixes.indices.reversed()) {
                    val f = fixes[i]
                    val d = haversineMeters(
                        candidate.latitude,
                        candidate.longitude,
                        f.latitude,
                        f.longitude
                    )
                    if (d <= STARTUP_CLUSTER_RADIUS_METERS) {
                        return f
                    }
                }
            }
        }

        return null
    }

    private fun writeOwnship(fix: OwnshipFix) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(fix.timeMillis))

        val traffic = drainQueue(trafficQueue)
        val uplink = drainQueue(uplinkQueue)

        val sb = StringBuilder()
        sb.append("""      <trkpt lat="${fix.latitude}" lon="${fix.longitude}">""").append('\n')
        sb.append("""        <time>$iso</time>""").append('\n')
        sb.append("""        <extensions>""").append('\n')

        if (traffic.isNotEmpty()) {
            sb.append("""          <adsb:traffic count="${traffic.size}">""").append('\n')
            for (pkt in traffic) {
                sb.append("""            <adsb:packet>$pkt</adsb:packet>""").append('\n')
            }
            sb.append("""          </adsb:traffic>""").append('\n')
        }

        if (uplink.isNotEmpty()) {
            sb.append("""          <adsb:uplink count="${uplink.size}">""").append('\n')
            for (pkt in uplink) {
                sb.append("""            <adsb:packet>$pkt</adsb:packet>""").append('\n')
            }
            sb.append("""          </adsb:uplink>""").append('\n')
        }

        sb.append("""        </extensions>""").append('\n')
        sb.append("""      </trkpt>""").append('\n')

        write(sb.toString())
    }

    fun close() {
        if (closed) return
        closed = true

        try {
            write(
                """
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent() + "\n"
            )
        } catch (_: Exception) {
        }

        try {
            outputStream?.flush()
        } catch (_: Exception) {
        }

        try {
            outputStream?.close()
        } catch (_: Exception) {
        }

        Log.d(TAG, "GPX log file closed: ${getLocationDescription()}")
    }

    private fun createOutput() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "adsb_log_$ts.gpx")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/ADS-B Logs")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val newUri = resolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            ) ?: throw RuntimeException("Unable to create GPX file in Documents")

            uri = newUri

            outputStream = resolver.openOutputStream(newUri)
                ?: throw RuntimeException("Unable to open GPX output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val publicDocsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

            val publicOutDir = File(publicDocsDir, "ADS-B Logs")

            val publicDirReady = try {
                publicOutDir.exists() || publicOutDir.mkdirs()
            } catch (_: Exception) {
                false
            }

            if (publicDirReady) {
                try {
                    val outFile = File(publicOutDir, "adsb_log_$ts.gpx")
                    outputStream = FileOutputStream(outFile, true)
                    absolutePath = outFile.absolutePath
                    Log.d(TAG, "Writing GPX to public Documents: $absolutePath")
                    return
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Public Documents write failed, falling back to app-specific storage",
                        e
                    )
                }
            } else {
                Log.w(
                    TAG,
                    "Could not create public Documents/ADS-B Logs, falling back to app-specific storage"
                )
            }

            val appDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir

            val appOutDir = File(appDocsDir, "ADS-B Logs")
            if (!appOutDir.exists() && !appOutDir.mkdirs()) {
                throw RuntimeException("Unable to create fallback GPX directory")
            }

            val outFile = File(appOutDir, "adsb_log_$ts.gpx")
            outputStream = FileOutputStream(outFile, true)
            absolutePath = outFile.absolutePath
            Log.d(TAG, "Writing GPX to app-specific Documents: $absolutePath")
        }
    }

    private fun decodeOwnship(packet: ByteArray): OwnshipDecodeResult {
        if (packet.size < 12) return OwnshipDecodeResult.TooShort

        val latRaw = readSigned24(packet, 6)
        val lonRaw = readSigned24(packet, 9)

        if (latRaw == 0 && lonRaw == 0) {
            return OwnshipDecodeResult.ZeroLatLon
        }

        val latitude = latRaw * 180.0 / 8388608.0
        val longitude = lonRaw * 180.0 / 8388608.0

        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            Log.w(TAG, "Ignoring invalid ownship lat/lon: $latitude, $longitude")
            return OwnshipDecodeResult.InvalidLatLon
        }

        return OwnshipDecodeResult.Success(
            OwnshipFix(
                timeMillis = System.currentTimeMillis(),
                latitude = latitude,
                longitude = longitude
            )
        )
    }

    private fun isReasonableFix(newFix: OwnshipFix): Boolean {
        val prev = lastAcceptedFix ?: return true

        val dtSec = (newFix.timeMillis - prev.timeMillis) / 1000.0
        if (dtSec <= 0.0) return true

        val distanceMeters = haversineMeters(
            prev.latitude,
            prev.longitude,
            newFix.latitude,
            newFix.longitude
        )

        val speedMps = distanceMeters / dtSec
        val speedKts = speedMps * 1.94384

        if (distanceMeters > MAX_REASONABLE_STEP_METERS && dtSec <= 2.0) {
            Log.w(
                TAG,
                "Rejected GPS jump: distance=${"%.1f".format(distanceMeters)} m in ${"%.2f".format(dtSec)} s"
            )
            return false
        }

        if (speedKts > MAX_REASONABLE_SPEED_KTS) {
            Log.w(
                TAG,
                "Rejected GPS jump: implied speed=${"%.1f".format(speedKts)} kt " +
                        "from (${prev.latitude}, ${prev.longitude}) to (${newFix.latitude}, ${newFix.longitude})"
            )
            return false
        }

        return true
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    private fun readSigned24(bytes: ByteArray, start: Int): Int {
        if (start + 2 >= bytes.size) return 0

        var value =
            ((bytes[start].toInt() and 0xFF) shl 16) or
                    ((bytes[start + 1].toInt() and 0xFF) shl 8) or
                    (bytes[start + 2].toInt() and 0xFF)

        if ((value and 0x800000) != 0) {
            value = value or -0x1000000
        }

        return value
    }

    private fun drainQueue(queue: ConcurrentLinkedQueue<String>): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val item = queue.poll() ?: break
            out.add(item)
        }
        return out
    }

    private fun write(text: String) {
        val os = outputStream ?: throw IllegalStateException("Output stream is not open")
        os.write(text.toByteArray(Charsets.UTF_8))
        os.flush()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}