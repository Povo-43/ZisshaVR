package dev.zissha.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * BLE GATT protocol shared by the ZisshaVR main app (Peripheral) and the remote controller app (Central).
 *
 * Frame layout (little endian):
 *   offset 0      : magic byte 0xA5
 *   offset 1      : protocol version
 *   offset 2      : sequence number (wraps around)
 *   offset 3      : command code
 *   offset 4..5   : payload length (u16)
 *   offset 6..    : payload bytes
 *   last 2 bytes  : CRC16/CCITT-FALSE over all preceding bytes
 */
object BleProtocol {
    /** Custom 128-bit GATT service UUID for ZisshaVR remote control. */
    const val SERVICE_UUID: String = "8f5d2101-6c3e-4a90-9b12-3f4a5b6c7d8e"

    /** Writable command characteristic (write from central -> peripheral). */
    const val COMMAND_CHAR_UUID: String = "8f5d2102-6c3e-4a90-9b12-3f4a5b6c7d8e"

    /** Notifiable status characteristic (notify from peripheral -> central). */
    const val STATUS_CHAR_UUID: String = "8f5d2103-6c3e-4a90-9b12-3f4a5b6c7d8e"

    /** Notifiable event characteristic (discrete events such as recording finalized). */
    const val EVENT_CHAR_UUID: String = "8f5d2104-6c3e-4a90-9b12-3f4a5b6c7d8e"

    const val MAGIC: Byte = 0xA5.toByte()
    const val VERSION: Byte = 0x01
    const val HEADER_SIZE = 6
    const val CRC_SIZE = 2
    const val MAX_PAYLOAD = 244 // fits a single ATT write on all transports

    /** Advertising is filtered by this short local name. */
    const val DEVICE_NAME = "ZisshaVR"

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF) shl 8
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun encode(seq: Int, command: Command, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val buf = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        buf[0] = MAGIC
        buf[1] = VERSION
        buf[2] = seq.toByte()
        buf[3] = command.code
        buf[4] = (payload.size and 0xFF).toByte()
        buf[5] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(buf, HEADER_SIZE)
        val crc = crc16(buf, 0, HEADER_SIZE + payload.size)
        buf[buf.size - 2] = (crc and 0xFF).toByte()
        buf[buf.size - 1] = ((crc shr 8) and 0xFF).toByte()
        return buf
    }

    /** @return decoded frame or null when the bytes are not a valid frame (noise, truncation, CRC mismatch). */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) return null
        if (bytes[0] != MAGIC || bytes[1] != VERSION) return null
        val len = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
        if (bytes.size != HEADER_SIZE + len + CRC_SIZE) return null
        if (len > MAX_PAYLOAD) return null
        val expected = crc16(bytes, 0, HEADER_SIZE + len)
        val actual = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        if (expected != actual) return null
        val command = Command.fromCode(bytes[3]) ?: return null
        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
        return Frame(
            seq = bytes[2].toInt() and 0xFF,
            command = command,
            payload = payload,
        )
    }

    data class Frame(val seq: Int, val command: Command, val payload: ByteArray)

    enum class Command(val code: Byte) {
        /** Central -> Peripheral. Payload: token ASCII bytes. Unlocks control. */
        AUTH(0x01),

        /** Peripheral -> Central. Payload: UTF-8 message. */
        AUTH_OK(0x02),

        /** Peripheral -> Central. Payload: UTF-8 message. */
        AUTH_FAIL(0x03),

        /** Central -> Peripheral. Payload: 1 byte OutputMode. */
        SET_MODE(0x10),

        /** Central -> Peripheral. No payload. */
        RECORD_START(0x11),

        /** Central -> Peripheral. No payload. */
        RECORD_STOP(0x12),

        /** Central -> Peripheral. No payload. */
        STREAM_START(0x13),

        /** Central -> Peripheral. No payload. */
        STREAM_STOP(0x14),

        /** Central -> Peripheral. No payload. */
        GET_STATUS(0x20),

        /** Peripheral -> Central. Payload: StatusPayload JSON. */
        STATUS(0x21),

        /** Central -> Peripheral. Payload: u32 bitrate (little endian). */
        SET_BITRATE(0x22),

        /** Both directions. No payload. */
        PING(0x30),

        /** Both directions. No payload. */
        PONG(0x31),

        /** Peripheral -> Central. Payload: UTF-8 message. */
        ERROR(0x40);

        companion object {
            fun fromCode(code: Byte): Command? = entries.firstOrNull { it.code == code }
        }
    }

    fun encodeStatus(seq: Int, status: StatusPayload): ByteArray =
        encode(seq, Command.STATUS, json.encodeToString(StatusPayload.serializer(), status).toByteArray(Charsets.UTF_8))

    fun decodeStatus(frame: Frame): StatusPayload? =
        runCatching { json.decodeFromString(StatusPayload.serializer(), frame.payload.toString(Charsets.UTF_8)) }.getOrNull()
}

enum class OutputMode { LIVE, FREEZE, PRIVACY }

@Serializable
data class StatusPayload(
    val mode: OutputMode,
    val recording: Boolean,
    val streaming: Boolean,
    val trackingHealthy: Boolean,
    val lod: Int,
    val fps: Float,
    val inferenceMs: Float,
    val thermalStatus: Int,
    val rtmpState: String,
    val bitrate: Int,
    val avatarName: String,
    val message: String = "",
)
