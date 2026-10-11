package com.example.pingsonar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PingProtocol {

    // Request message ID 1212 (distance_simple)
    // Format: 'B' 'R' | payload_len (2B) | msg_id (2B) | src (1B) | dst (1B) | payload (2B req_id) | crc (2B)
    val REQUEST_DISTANCE_SIMPLE: ByteArray = byteArrayOf(
        0x42, 0x52,             // 'B', 'R'
        0x02, 0x00,             // Payload length: 2 bytes
        0xBC.toByte(), 0x04,   // Message ID: 1212 (0x04BC little-endian)
        0x00,                   // Src ID
        0x00,                   // Dst ID
        0xBC.toByte(), 0x04,   // Requested ID: 1212
        0xE2.toByte(), 0x01    // Checksum
    )

    fun calculateChecksum(data: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length) {
            sum += (data[i].toInt() and 0xFF)
        }
        return sum and 0xFFFF
    }

    // Mengurai paket Ping Protocol dari buffer
    fun parseBuffer(buffer: MutableList<Byte>): Pair<Float, Int>? {
        while (buffer.size >= 10) {
            // Cari header 'B' (0x42) dan 'R' (0x52)
            if (buffer[0] != 0x42.toByte() || buffer[1] != 0x52.toByte()) {
                buffer.removeAt(0)
                continue
            }

            val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            val fullPacketLength = 8 + payloadLength + 2 // Header (8) + Payload + CRC (2)

            if (buffer.size < fullPacketLength) {
                // Tunggu byte berikutnya sampai paket utuh
                return null
            }

            val packet = ByteArray(fullPacketLength)
            for (i in 0 until fullPacketLength) {
                packet[i] = buffer[i]
            }

            // Validasi Checksum
            val expectedCrc = (packet[fullPacketLength - 2].toInt() and 0xFF) or
                    ((packet[fullPacketLength - 1].toInt() and 0xFF) shl 8)
            val calculatedCrc = calculateChecksum(packet, fullPacketLength - 2)

            if (expectedCrc == calculatedCrc) {
                val msgId = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
                
                // Message ID 1212: distance_simple
                if (msgId == 1212 && payloadLength >= 5) {
                    val payload = ByteBuffer.wrap(packet, 8, payloadLength).order(ByteOrder.LITTLE_ENDIAN)
                    val distanceMm = payload.int // uint32 (mm)
                    val confidence = payload.get().toInt() and 0xFF // uint8 (%)

                    val distanceMeters = distanceMm / 1000.0f

                    // Hapus paket yang sudah berhasil diproses dari buffer
                    repeat(fullPacketLength) { buffer.removeAt(0) }
                    return Pair(distanceMeters, confidence)
                }
            }

            // Jika CRC salah atau bukan target, buang byte pertama lalu scan ulang
            buffer.removeAt(0)
        }
        return null
    }
}
