package org.sarangan.ADSBMonitor

const val TAG: String = "ADSBMonitor"

val stratusDataOpen: ByteArray = byteArrayOf(
    0xC2.toByte(),
    0x53.toByte(),
    0xFF.toByte(),
    0x56.toByte(),
    0x01.toByte(),
    0x01.toByte(),
    0x6E.toByte(),
    0x37.toByte()
)

val stratusDataClose: ByteArray = byteArrayOf(
    0xC2.toByte(),
    0x53.toByte(),
    0xFF.toByte(),
    0x56.toByte(),
    0x01.toByte(),
    0x00.toByte(),
    0x6D.toByte(),
    0x36.toByte()
)

object ADSBActions {
    const val ACTION_START = "org.sarangan.ADSBMonitor.action.START"
    const val ACTION_STOP = "org.sarangan.ADSBMonitor.action.STOP"
    const val ACTION_SET_MODE = "org.sarangan.ADSBMonitor.action.SET_MODE"
    const val ACTION_SET_LOGGING = "org.sarangan.ADSBMonitor.action.SET_LOGGING"

    const val ACTION_STATUS = "org.sarangan.ADSBMonitor.action.STATUS"
    const val ACTION_PACKET = "org.sarangan.ADSBMonitor.action.PACKET"
    const val ACTION_ERROR = "org.sarangan.ADSBMonitor.action.ERROR"
}

object ADSBExtras {
    const val EXTRA_OPEN_GDL = "open_gdl"
    const val EXTRA_LOGGING_ENABLED = "logging_enabled"
    const val EXTRA_ENABLE_PACKET_REJECTION = "enable_packet_rejection"

    const val EXTRA_PACKET_TYPE = "packet_type"
    const val EXTRA_COUNT = "count"
    const val EXTRA_ERROR_TEXT = "error_text"
}