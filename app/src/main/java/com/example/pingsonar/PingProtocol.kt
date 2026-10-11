package com.example.pingsonar

object PingProtocol {
    // Request Distance Simple (Msg ID: 1211 = 0x04BB)
    val REQUEST_DISTANCE_SIMPLE = byteArrayOf(
        0x42.toByte(), 0x52.toByte(), // Header: 'B', 'R'
        0x02.toByte(), 0x00.toByte(), // Payload Length: 2 byte
        0x00.toByte(), 0x00.toByte(), // Msg ID: 0 (Request)
        0x00.toByte(), 0x00.toByte(), // Src ID, Dst ID
        0xBB.toByte(), 0x04.toByte(), // Requested ID: 1211 (0x04BB)
        0x00.toByte(), 0x00.toByte(), // Reserved
        0xF0.toByte(), 0x00.toByte()  // Checksum
    )

    fun parseDistanceSimple(data: ByteArray, length: Int): Pair<Float, Int>? {
        // Cari header 'BR' (0x42, 0x52) di aliran data serial
        for (i in 0 until length - 13) {
            if (data[i] == 0x42.toByte() && data[i + 1] == 0x52.toByte()) {
                val payloadLen = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
                val msgId = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)

                // Message ID 1211 (distance_simple) dengan payload 6 byte
                if (msgId == 1211 && payloadLen >= 6 && (i + 8 + 6) <= length) {
                    val p = i + 8
                    // Distance: uint32 little-endian (mm)
                    val distMm = (data[p].toLong() and 0xFF) or
                            ((data[p + 1].toLong() and 0xFF) shl 8) or
                            ((data[p + 2].toLong() and 0xFF) shl 16) or
                            ((data[p + 3].toLong() and 0xFF) shl 24)

                    // Confidence: uint16 little-endian (%)
                    val confidence = (data[p + 4].toInt() and 0xFF) or
                            ((data[p + 5].toInt() and 0xFF) shl 8)

                    val distM = distMm / 1000.0f
                    return Pair(distM, confidence)
                }
            }
        }
        return null
    }
}
