package app.votube.sabr.parser

import video_streaming.UmpPartId.UMPPartId

/**
 * Encodes a variable-sized integer per the UMP format spec
 * (https://github.com/gsuberland/UMP_Format), mirroring the decoding logic in
 * [StreamingUmpReader] and [UmpParser].
 */
internal fun encodeVarint(value: UInt): ByteArray {
    val size = when {
        value <= 0x7Fu -> 1
        value <= 0x3FFFu -> 2
        value <= 0x1FFFFFu -> 3
        value <= 0x0FFFFFFFu -> 4
        else -> 5
    }
    if (size == 1) {
        return byteArrayOf(value.toByte())
    }
    // Leading ones in the first byte indicate the total varint size.
    // For size 5 the first byte carries no payload bits.
    val marker = if (size == 5) 0xF0 else (0xFF shl (9 - size)) and 0xFF
    val shift = 8 - size
    val first = marker or ((value and ((1u shl shift) - 1u)).toInt())
    val bytes = mutableListOf<Byte>(first.toByte())
    var remaining = value shr shift
    repeat(size - 1) {
        bytes.add((remaining and 0xFFu).toByte())
        remaining = remaining shr 8
    }
    return bytes.toByteArray()
}

/** Encodes a single UMP part as varint(type) + varint(size) + data. */
internal fun encodePart(type: UMPPartId, data: ByteArray): ByteArray =
    encodeVarint(type.number.toUInt()) + encodeVarint(data.size.toUInt()) + data

/** Concatenates byte arrays. */
internal fun concat(vararg arrays: ByteArray): ByteArray {
    val result = ByteArray(arrays.sumOf { it.size })
    var offset = 0
    for (array in arrays) {
        array.copyInto(result, offset)
        offset += array.size
    }
    return result
}
