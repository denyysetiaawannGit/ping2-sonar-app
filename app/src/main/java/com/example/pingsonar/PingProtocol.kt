package com.example.pingsonar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PingProtocol {

    // Request message ID 1212 (distance_simple)
    val REQUEST_DISTANCE_SIMPLE: ByteArray = byteArrayOf(
        0x42, 0x52,
        0x02, 0x00,
        0xBC.toByte(), 0x04,
        0x00,
        0x00,
        0xBC.toByte(), 0x04,
        0xE2.toByte(), 0x01
    )

    private fun calculateChecksum(data: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length) {
            sum += (data[i].toInt() and 0xFF)
        }
        return sum and 0xFFFF
    }

    fun parseBuffer(buffer: MutableList<Byte>): Pair<Float, Int>? {
        while (buffer.size >= 10) {
            if (buffer[0] != 0x42.toByte() || buffer[1] != 0x52.toByte()) {
                buffer.removeAt(0)
                continue
            }

            val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            val fullPacketLength = 8 + payloadLength + 2

            if (buffer.size < fullPacketLength) {
                return null
            }

            val packet = ByteArray(fullPacketLength)
            for (i in 0 until fullPacketLength) {
                packet[i] = buffer[i]
            }

            val expectedCrc = (packet[fullPacketLength - 2].toInt() and 0xFF) or
                    ((packet[fullPacketLength - 1].toInt() and 0xFF) shl 8)
            val calculatedCrc = calculateChecksum(packet, fullPacketLength - 2)

            if (expectedCrc == calculatedCrc) {
                val msgId = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)

                if (msgId == 1212 && payloadLength >= 5) {
                    val payload = ByteBuffer.wrap(packet, 8, payloadLength).order(ByteOrder.LITTLE_ENDIAN)
                    val distanceMm = payload.int
                    val confidence = payload.get().toInt() and 0xFF
                    val distanceMeters = distanceMm.toFloat() / 1000.0f

                    for (i in 0 until fullPacketLength) {
                        buffer.removeAt(0)
                    }
                    return Pair(distanceMeters, confidence)
                }
            }

            buffer.removeAt(0)
        }
        return null
    }
}
