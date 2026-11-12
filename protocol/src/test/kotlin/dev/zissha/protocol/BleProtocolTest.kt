package dev.zissha.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolTest {

    @Test
    fun `encode-decode round trip preserves frame`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = BleProtocol.encode(7, BleProtocol.Command.SET_MODE, payload)
        val frame = BleProtocol.decode(encoded)
        assertTrue(frame != null)
        assertEquals(7, frame!!.seq)
        assertEquals(BleProtocol.Command.SET_MODE, frame.command)
        assertTrue(payload.contentEquals(frame.payload))
    }

    @Test
    fun `empty payload round trip`() {
        val encoded = BleProtocol.encode(0, BleProtocol.Command.PING)
        val frame = BleProtocol.decode(encoded)
        assertTrue(frame != null)
        assertEquals(BleProtocol.Command.PING, frame!!.command)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `corrupted crc is rejected`() {
        val encoded = BleProtocol.encode(1, BleProtocol.Command.RECORD_START)
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0x55).toByte()
        assertNull(BleProtocol.decode(encoded))
    }

    @Test
    fun `corrupted magic is rejected`() {
        val encoded = BleProtocol.encode(1, BleProtocol.Command.GET_STATUS)
        encoded[0] = 0x00
        assertNull(BleProtocol.decode(encoded))
    }

    @Test
    fun `truncated frame is rejected`() {
        val encoded = BleProtocol.encode(1, BleProtocol.Command.SET_BITRATE, byteArrayOf(1, 2, 3, 4))
        assertNull(BleProtocol.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun `unknown command code is rejected`() {
        val encoded = BleProtocol.encode(1, BleProtocol.Command.PING)
        encoded[3] = 0x7F
        // Recompute CRC so only the command is invalid.
        val crc = BleProtocol.crc16(encoded, 0, encoded.size - 2)
        encoded[encoded.size - 2] = (crc and 0xFF).toByte()
        encoded[encoded.size - 1] = ((crc shr 8) and 0xFF).toByte()
        assertNull(BleProtocol.decode(encoded))
    }

    @Test
    fun `oversized payload is rejected by encode`() {
        var thrown = false
        try {
            BleProtocol.encode(1, BleProtocol.Command.PING, ByteArray(BleProtocol.MAX_PAYLOAD + 1))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `status payload json round trip`() {
        val status = StatusPayload(
            mode = OutputMode.FREEZE,
            recording = true,
            streaming = false,
            trackingHealthy = false,
            lod = 1,
            fps = 29.7f,
            inferenceMs = 12.3f,
            thermalStatus = 1,
            rtmpState = "DISCONNECTED",
            bitrate = 4_000_000,
            avatarName = "sample.vrm",
            message = "test",
        )
        val encoded = BleProtocol.encodeStatus(3, status)
        val frame = BleProtocol.decode(encoded)
        assertTrue(frame != null)
        assertEquals(BleProtocol.Command.STATUS, frame!!.command)
        val decoded = BleProtocol.decodeStatus(frame)
        assertTrue(decoded != null)
        assertEquals(status, decoded)
    }

    @Test
    fun `crc16 is deterministic and differs for different data`() {
        val a = BleProtocol.crc16(byteArrayOf(0x00))
        val b = BleProtocol.crc16(byteArrayOf(0x00))
        val c = BleProtocol.crc16(byteArrayOf(0x01))
        assertEquals(a, b)
        assertFalse(a == c)
    }
}
