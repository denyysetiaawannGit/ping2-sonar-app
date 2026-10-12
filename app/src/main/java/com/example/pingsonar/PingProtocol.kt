package com.example.pingsonar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PingProtocol {

    fun createPacket(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val payloadLen = payload.size
        val packetLen = 8 + payloadLen + 2
        val buffer = ByteBuffer.allocate(packetLen).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0x42.toByte()) // 'B'
        buffer.put(0x52.toByte()) // 'R'
        buffer.putShort(payloadLen.toShort())
        buffer.putShort(msgId.toShort())
        buffer.put(0.toByte()) // Src
        buffer.put(0.toByte()) // Dst

        if (payloadLen > 0) {
            buffer.put(payload)
        }

        val raw = buffer.array()
        var checksum = 0
        for (i in 0 until (8 + payloadLen)) {
            checksum += (raw[i].toInt() and 0xFF)
        }
        checksum = checksum and 0xFFFF

        buffer.putShort(checksum.toShort())
        return buffer.array()
    }

    fun getContinuousStartPacket(): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(1212.toShort())
        return createPacket(1003, payload.array())
    }

    fun getPollDistanceSimplePacket(): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(1212.toShort())
        return createPacket(1, payload.array())
    }

    private fun calculateChecksum(data: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length) {
            sum += (data[i].toInt() and 0xFF)
        }
        return sum and 0xFFFF
    }

    fun parseBuffer(buffer: MutableList<Byte>): Pair<Float, Int>? {
        while (buffer.size >= 10) {
            // Cari header 'B' 'R'
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
                val payload = ByteBuffer.wrap(packet, 8, payloadLength).order(ByteOrder.LITTLE_ENDIAN)

                var parsedResult: Pair<Float, Int>? = null

                when (msgId) {
                    1212 -> { // distance_simple
                        if (payloadLength >= 5) {
                            val distMm = payload.int
                            val conf = payload.get().toInt() and 0xFF
                            parsedResult = Pair(distMm.toFloat() / 1000.0f, conf)
                        }
                    }
                    1211 -> { // distance
                        if (payloadLength >= 10) {
                            val distMm = payload.int
                            val conf = payload.get().toInt() and 0xFF
                            parsedResult = Pair(distMm.toFloat() / 1000.0f, conf)
                        }
                    }
                    1300 -> { // profile
                        if (payloadLength >= 6) {
                            val distMm = payload.int
                            val conf = payload.short.toInt() and 0xFFFF
                            parsedResult = Pair(distMm.toFloat() / 1000.0f, conf)
                        }
                    }
                }

                for (i in 0 until fullPacketLength) {
                    buffer.removeAt(0)
                }

                if (parsedResult != null) {
                    return parsedResult
                }
            } else {
                buffer.removeAt(0)
            }
        }
        return null
    }
}
