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

data class OwnshipFix(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double
)

class GpxLogger(private val context: Context) {

    companion object {
        private const val TAG = "ADSBMonitor"
    }

    private val trafficQueue = ConcurrentLinkedQueue<String>()
    private val uplinkQueue = ConcurrentLinkedQueue<String>()

    private var outputStream: OutputStream? = null
    private var uri: Uri? = null
    private var absolutePath: String? = null
    private var closed = false

    init {
        createOutput()
        write(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"
                 creator="ADSBSaver"
                 xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:adsb="https://org.sarangan.adsbsaver/adsb">
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

    fun writeOwnshipIfPossible(packet: ByteArray) {
        if (closed) return
        val fix = decodeOwnship(packet) ?: return
        writeOwnship(fix)
    }

    fun getLocationDescription(): String {
        absolutePath?.let { return it }
        uri?.let { return it.toString() }
        return "unknown"
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

            val newUri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw RuntimeException("Unable to create GPX file in Documents")

            uri = newUri

            outputStream = resolver.openOutputStream(newUri)
                ?: throw RuntimeException("Unable to open GPX output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val outDir = File(docsDir, "ADS-B Logs")
            if (!outDir.exists()) {
                outDir.mkdirs()
            }

            val outFile = File(outDir, "adsb_log_$ts.gpx")
            outputStream = FileOutputStream(outFile, true)
            absolutePath = outFile.absolutePath
        }
    }

    private fun decodeOwnship(packet: ByteArray): OwnshipFix? {
        if (packet.size < 12) return null

        // packet[0] = 0x7E frame flag
        // packet[1] = message ID
        // packet[2] = status/address type
        // packet[3..5] = participant address
        // packet[6..8] = latitude
        // packet[9..11] = longitude
        val latRaw = readSigned24(packet, 6)
        val lonRaw = readSigned24(packet, 9)

        if (latRaw == 0 && lonRaw == 0) return null

        val latitude = latRaw * 180.0 / 8388608.0
        val longitude = lonRaw * 180.0 / 8388608.0

        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            Log.w(TAG, "Ignoring invalid ownship lat/lon: $latitude, $longitude")
            return null
        }

        return OwnshipFix(
            timeMillis = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude
        )
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