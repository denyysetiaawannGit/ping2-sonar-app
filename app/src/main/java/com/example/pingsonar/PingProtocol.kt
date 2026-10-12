package com.example.pingsonar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PingProtocol {

    var lastDebugMsg: String = ""

    fun createPacket(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val payloadLen = payload.size
        val totalLen = 8 + payloadLen + 2
        val packet = ByteArray(totalLen)

        packet[0] = 0x42.toByte() // 'B'
        packet[1] = 0x52.toByte() // 'R'
        packet[2] = (payloadLen and 0xFF).toByte()
        packet[3] = ((payloadLen shr 8) and 0xFF).toByte()
        packet[4] = (msgId and 0xFF).toByte()
        packet[5] = ((msgId shr 8) and 0xFF).toByte()
        packet[6] = 0x00
        packet[7] = 0x00

        System.arraycopy(payload, 0, packet, 8, payloadLen)

        var sum = 0
        for (i in 0 until (8 + payloadLen)) {
            sum += (packet[i].toInt() and 0xFF)
        }
        val crc = sum and 0xFFFF

        packet[totalLen - 2] = (crc and 0xFF).toByte()
        packet[totalLen - 1] = ((crc shr 8) and 0xFF).toByte()

        return packet
    }

    fun getPollDistanceSimplePacket(): ByteArray {
        val payload = byteArrayOf(
            (1212 and 0xFF).toByte(),
            ((1212 shr 8) and 0xFF).toByte()
        )
        return createPacket(6, payload)
    }

    fun getContinuousStartPacket(): ByteArray {
        val payload = byteArrayOf(
            (1212 and 0xFF).toByte(),
            ((1212 shr 8) and 0xFF).toByte()
        )
        return createPacket(1003, payload)
    }

    fun parseBuffer(buffer: MutableList<Byte>): Pair<Float, Int>? {
        while (buffer.size >= 10) {
            // Cari awalan header 'B' 'R'
            if (buffer[0] != 0x42.toByte() || buffer[1] != 0x52.toByte()) {
                buffer.removeAt(0)
                continue
            }

            val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            val fullPacketLength = 8 + payloadLength + 2

            // Lindungi dari panjang payload anomali
            if (payloadLength > 1024) {
                buffer.removeAt(0)
                continue
            }

            // Tunggu hingga paket utuh terkumpul di buffer
            if (buffer.size < fullPacketLength) {
                return null
            }

            val packet = ByteArray(fullPacketLength)
            for (i in 0 until fullPacketLength) {
                packet[i] = buffer[i]
            }

            val msgId = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
            lastDebugMsg = "ID:$msgId L:$payloadLength"

            val payload = ByteBuffer.wrap(packet, 8, payloadLength).order(ByteOrder.LITTLE_ENDIAN)
            var result: Pair<Float, Int>? = null

            when (msgId) {
                1212 -> { // distance_simple: distance (uint32 mm), confidence (uint8)
                    if (payloadLength >= 5) {
                        val distMm = payload.int
                        val conf = payload.get().toInt() and 0xFF
                        result = Pair(distMm.toFloat() / 1000.0f, conf)
                    }
                }
                1211 -> { // distance: distance (uint32 mm), confidence (uint8)
                    if (payloadLength >= 5) {
                        val distMm = payload.int
                        val conf = payload.get().toInt() and 0xFF
                        result = Pair(distMm.toFloat() / 1000.0f, conf)
                    }
                }
                1300 -> { // profile: distance (uint32 mm), confidence (uint16)
                    if (payloadLength >= 6) {
                        val distMm = payload.int
                        val conf = payload.short.toInt() and 0xFFFF
                        result = Pair(distMm.toFloat() / 1000.0f, conf)
                    }
                }
                else -> {
                    // Coba baca uint32 pertama jika ukuran payload minimal 4 byte
                    if (payloadLength >= 4) {
                        val possibleDistMm = payload.int
                        if (possibleDistMm in 100..100000) { // Rentang wajar: 10 cm - 100 m
                            result = Pair(possibleDistMm.toFloat() / 1000.0f, 100)
                        }
                    }
                }
            }

            // Hapus paket yang selesai diproses dari buffer
            for (i in 0 until fullPacketLength) {
                buffer.removeAt(0)
            }

            if (result != null) {
                return result
            }
        }
        return null
    }
}
