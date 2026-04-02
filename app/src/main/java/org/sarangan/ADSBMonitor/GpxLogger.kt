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

data class OwnshipFix(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double
)

enum class OwnshipWriteResult {
    WRITTEN,
    REJECTED_TOO_SHORT,
    REJECTED_INVALID_LATLON,
    LOGGER_CLOSED
}

sealed class OwnshipDecodeResult {
    data class Success(val fix: OwnshipFix) : OwnshipDecodeResult()
    data object TooShort : OwnshipDecodeResult()
    data object InvalidLatLon : OwnshipDecodeResult()
}

class GpxLogger(
    private val context: Context
) {

    companion object {
        private const val TAG = "ADSBMonitor"
    }

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

    fun writeOwnshipIfPossible(packet: ByteArray): OwnshipWriteResult {
        if (closed) {
            Log.w(TAG, "writeOwnshipIfPossible called while logger is closed")
            return OwnshipWriteResult.LOGGER_CLOSED
        }

        return when (val decoded = decodeOwnship(packet)) {
            is OwnshipDecodeResult.Success -> {
                writeTrackPoint(decoded.fix)
                OwnshipWriteResult.WRITTEN
            }

            OwnshipDecodeResult.TooShort -> {
                Log.w(TAG, "Rejected ownship packet: too short len=${packet.size}")
                OwnshipWriteResult.REJECTED_TOO_SHORT
            }

            OwnshipDecodeResult.InvalidLatLon -> {
                Log.w(TAG, "Rejected ownship packet: invalid lat/lon")
                OwnshipWriteResult.REJECTED_INVALID_LATLON
            }
        }
    }

    fun writeTrafficEvent(packet: ByteArray) {
        if (closed) {
            Log.w(TAG, "writeTrafficEvent called while logger is closed")
            return
        }
        writeEvent("traffic", packet)
    }

    fun writeUplinkEvent(packet: ByteArray) {
        if (closed) {
            Log.w(TAG, "writeUplinkEvent called while logger is closed")
            return
        }
        writeEvent("uplink", packet)
    }

    fun getLocationDescription(): String {
        absolutePath?.let { return it }
        uri?.let { return it.toString() }
        return "unknown"
    }

    private fun writeTrackPoint(fix: OwnshipFix) {
        val iso = isoTime(fix.timeMillis)

        val sb = StringBuilder()
        sb.append("""      <trkpt lat="${fix.latitude}" lon="${fix.longitude}">""").append('\n')
        sb.append("""        <time>$iso</time>""").append('\n')
        sb.append("""      </trkpt>""").append('\n')

        write(sb.toString())
    }

    private fun writeEvent(type: String, packet: ByteArray) {
        val iso = isoTime(System.currentTimeMillis())
        val hex = packet.toHexString()

        val sb = StringBuilder()
        sb.append("""      <extensions>""").append('\n')
        sb.append("""        <adsb:event time="$iso" type="$type">""").append('\n')
        sb.append("""          <adsb:packet>$hex</adsb:packet>""").append('\n')
        sb.append("""        </adsb:event>""").append('\n')
        sb.append("""      </extensions>""").append('\n')

        write(sb.toString())
    }

    private fun isoTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timeMillis))
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

    private fun write(text: String) {
        val os = outputStream ?: throw IllegalStateException("Output stream is not open")
        os.write(text.toByteArray(Charsets.UTF_8))
        os.flush()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}