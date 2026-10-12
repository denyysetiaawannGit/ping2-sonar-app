package com.example.pingsonar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PingProtocol {

    /**
     * Membuat paket valid Ping-Protocol lengkap dengan checksum 16-bit
     * Header: 'B', 'R' (0x42, 0x52)
     */
    fun createPacket(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val payloadLen = payload.size
        val packetLen = 8 + payloadLen + 2 // 8 byte header + payload + 2 byte checksum
        val buffer = ByteBuffer.allocate(packetLen).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0x42.toByte()) // 'B'
        buffer.put(0x52.toByte()) // 'R'
        buffer.putShort(payloadLen.toShort())
        buffer.putShort(msgId.toShort())
        buffer.put(0.toByte()) // Src ID
        buffer.put(0.toByte()) // Dst ID

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

    /**
     * Command 1003 (control_continuous_start):
     * Memerintahkan sensor otomatis menyemburkan pesan ID 1212 (distance_simple)
     */
    fun getContinuousStartPacket(): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(1212.toShort())
        return createPacket(1003, payload.array())
    }

    /**
     * General Request (Msg ID 1):
     * Meminta sensor mengirimkan pesan ID 1212 (distance_simple) secara berkala
     */
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

    /**
     * Membaca dan memvalidasi buffer serial:
     * Mendukung ID 1212 (distance_simple) dan ID 1300 (profile bawaan Ping Viewer)
     */
    fun parseBuffer(buffer: MutableList<Byte>): Pair<Float, Int>? {
        while (buffer.size >= 10) {
            if (buffer[0] != 0x42.toByte() || buffer[1] != 0x52.toByte()) {
                buffer.removeAt(0)
                continue
            }

            val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            val fullPacketLength = 8 + payloadLength + 2

            if (buffer.size < fullPacketLength) {
                return null // Tunggu sisa byte tiba
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

                if (msgId == 1212 && payloadLength >= 5) {
                    // ID 1212: distance_simple -> distance (uint32, mm), confidence (uint8, %)
                    val distMm = payload.int
                    val conf = payload.get().toInt() and 0xFF
                    parsedResult = Pair(distMm.toFloat() / 1000.0f, conf)
                } else if (msgId == 1300 && payloadLength >= 6) {
                    // ID 1300: profile (format dari Ping Viewer) -> distance (uint32, mm), confidence (uint16, %)
                    val distMm = payload.int
                    val conf = payload.short.toInt() and 0xFFFF
                    parsedResult = Pair(distMm.toFloat() / 1000.0f, conf)
                }

                // Buang paket valid yang selesai diproses dari buffer
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
