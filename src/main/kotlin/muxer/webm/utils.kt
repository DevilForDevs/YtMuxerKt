package muxer.webm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

fun encodeVInt(value: Long, isId: Boolean = false): ByteArray {
    if (isId) {
        // EBML IDs: use 1–4 bytes, no length bit masking
        val bytes = ByteArrayOutputStream()
        when {
            value <= 0xFF -> bytes.write(value.toInt())
            value <= 0xFFFF -> {
                bytes.write((value shr 8).toInt())
                bytes.write((value and 0xFF).toInt())
            }
            value <= 0xFFFFFF -> {
                bytes.write((value shr 16).toInt())
                bytes.write((value shr 8 and 0xFF).toInt())
                bytes.write((value and 0xFF).toInt())
            }
            else -> {
                bytes.write((value shr 24).toInt())
                bytes.write((value shr 16 and 0xFF).toInt())
                bytes.write((value shr 8 and 0xFF).toInt())
                bytes.write((value and 0xFF).toInt())
            }
        }
        return bytes.toByteArray()
    }

    // For sizes (VINTs)
    var length = 1
    var tmp = value
    while (tmp >= (1L shl (7 * length))) length++

    val encoded = ByteArray(length)
    for (i in 0 until length) {
        encoded[length - 1 - i] = (value shr (8 * i) and 0xFF).toByte()
    }
    encoded[0] = (encoded[0].toInt() or (0x80 shr (length - 1))).toByte()
    return encoded
}

fun writeElement(out: DataOutputStream, id: Long, value: ByteArray) {
    out.write(encodeVInt(id, isId = true))
    out.write(encodeVInt(value.size.toLong()))
    out.write(value)
}

fun buildEbmlHeader(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val data = DataOutputStream(buffer)

    // EBMLVersion (0x4286)
    writeElement(data, 0x4286, byteArrayOf(0x01))
    // EBMLReadVersion (0x42F7)
    writeElement(data, 0x42F7, byteArrayOf(0x01))
    // EBMLMaxIDLength (0x42F2)
    writeElement(data, 0x42F2, byteArrayOf(0x04))
    // EBMLMaxSizeLength (0x42F3)
    writeElement(data, 0x42F3, byteArrayOf(0x08))
    // DocType (0x4282) - "webm"
    writeElement(data, 0x4282, "webm".toByteArray(Charsets.US_ASCII))
    // DocTypeVersion (0x4287)
    writeElement(data, 0x4287, byteArrayOf(0x04))
    // DocTypeReadVersion (0x4285)
    writeElement(data, 0x4285, byteArrayOf(0x02))

    val innerBytes = buffer.toByteArray()

    // Wrap in EBML master element (0x1A45DFA3)
    val header = ByteArrayOutputStream()
    val headerData = DataOutputStream(header)
    headerData.write(encodeVInt(0x1A45DFA3, isId = true))
    headerData.write(encodeVInt(innerBytes.size.toLong()))
    headerData.write(innerBytes)

    return header.toByteArray()
}

fun convertBytes(sizeInBytes: Long): String {
    val kilobyte = 1024
    val megabyte = kilobyte * 1024
    val gigabyte = megabyte * 1024

    return when {
        sizeInBytes >= gigabyte -> String.format("%.2f GB", sizeInBytes.toDouble() / gigabyte)
        sizeInBytes >= megabyte -> String.format("%.2f MB", sizeInBytes.toDouble() / megabyte)
        sizeInBytes >= kilobyte -> String.format("%.2f KB", sizeInBytes.toDouble() / kilobyte)
        else -> "$sizeInBytes Bytes"
    }
}

fun readElementId(reader: RandomAccessFile): Long? {
    val firstByte = reader.readUnsignedByte()
    if (firstByte == -1) return null

    var mask = 0x80
    var length = 1
    while (length <= 4) {
        if (firstByte and mask != 0) break
        mask = mask shr 1
        length++
    }

    var value = firstByte.toLong()
    for (i in 1 until length)
        value = (value shl 8) or reader.readUnsignedByte().toLong()

    return value
}
fun readElementSize(reader: RandomAccessFile): Long? {
    val firstByte = reader.readUnsignedByte()
    if (firstByte == -1) return null


    var mask = 0x80
    var length = 1
    while (length <= 8) {
        if (firstByte and mask != 0) break
        mask = mask shr 1
        length++
    }

    var value = (firstByte and (mask - 1)).toLong()

    for (i in 1 until length)
        value = (value shl 8) or reader.readUnsignedByte().toLong()

    return value
}

fun readUInt(reader: RandomAccessFile, size: Long): Long {
    var value = 0L
    for (i in 0 until size) {
        val b = reader.read()
        if (b == -1) break
        value = (value shl 8) or (b.toLong() and 0xFF)
    }
    return value
}

fun parseClusterInfo(
    startOffset: Long,
    clusterSize: Long,
    reader: RandomAccessFile,
    doLogging: Boolean
): Pair<Long?, Int> {
    var offset = startOffset
    val endOffset = startOffset + clusterSize
    var blockCount = 0
    var clusterTimecode: Long? = null

    while (offset < endOffset) {
        reader.seek(offset)
        val id = readElementId(reader) ?: break
        val size = readElementSize(reader) ?: break
        val headerSize = reader.filePointer - offset
        val contentOffset = offset + headerSize

        when (id) {
            0xE7L -> { // Cluster Timecode
                reader.seek(contentOffset)
                clusterTimecode = readUInt(reader, size)
            }

            0xA3L, 0xA0L -> { // SimpleBlock or BlockGroup
                blockCount++
                reader.seek(contentOffset)
                val blockData = ByteArray(size.toInt())
                reader.readFully(blockData)

                val blockStream = ByteArrayInputStream(blockData)
                val trackNumber = readVInt(blockStream)
                val blockTimecode = ((blockStream.read() shl 8) or blockStream.read()).toLong()
                val flags = blockStream.read()
                val isKeyframe = flags and 0x80 != 0
                val absoluteTime = (clusterTimecode ?: 0L) + blockTimecode

                if (doLogging) {
                    println(
                        "Block Info => Track $trackNumber  " +
                                "Keyframe: $isKeyframe  "+
                                "Timecode $blockTimecode  " +
                                "Absolute $absoluteTime  "
                    )
                }
            }
        }

        offset = contentOffset + size
    }

    return Pair(clusterTimecode, blockCount)
}




fun readVInt(stream: InputStream): Pair<Int, Int> {
    val firstByte = stream.read()
    if (firstByte == -1) return 0 to 0

    var mask = 0x80
    var length = 1
    while (length <= 8) {
        if (firstByte and mask != 0) break
        mask = mask shr 1
        length++
    }

    var value = firstByte and (mask - 1)
    repeat(length - 1) {
        val next = stream.read()
        value = (value shl 8) or next
    }

    return value to length
}

data class BlockEntry(
    val trackNumber: Int,
    val trackType: String,
    val clusterTimecode: Long,
    val blockTimecode: Long,
    val absoluteTimecode: Long,
    val isKeyframe: Boolean,
    val frameAbsoluteOffset: Long,
    val frameSize: Long,
    val rf: RandomAccessFile
)

data class ElementInfo(
    val id: Long,
    val contentOffset: Long,
    val contentSize: Long,
    val endOffset: Long
)

data class EBMLInfo(
    var timecodeScale: Long = 1000000L,
    var duration: Double = 0.0,
    var muxingApp: String = "",
    var writingApp: String = ""
)

data class TrackInfo(
    var number: Long = -1,
    var type: Long = -1,
    var codecID: String = "",
    var codecName: String = "",
    var language: String = "und",
    var width: Long = 0,
    var height: Long = 0,
    var samplingFrequency: Double = 0.0,
    var channels: Long = 0,
    var codecPrivate: ByteArray = ByteArray(4) { 0x00 }

)

data class CueEntry(
    var cueTime: Long = 0,
    var cueTrack: Long = 0,
    var cueClusterPosition: Long = 0
)

data class ClusterInfo(
    var timecode: Long = 0,
    var blocks: Int = 0,
    var offset: Long = 0,
    var size: Long = 0
)



