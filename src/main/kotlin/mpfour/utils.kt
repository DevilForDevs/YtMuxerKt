package mpfour

import mpfour.models.TfdtBox
import mpfour.models.TfhdBox
import org.ytmuxer.webm.convertBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder



data class BoxHeader(
    val type: String,
    val size: Long,
    val startOffset: Long,
    val payloadOffset: Long,
    val payloadSize: Long
)

data class VideoTrackInfo(
    var stsdBox: ByteArray? = null,
    var width: Int = 0,
    var height: Int = 0,
    var handlerType: String = "vide"
)

data class MoofBox(
    val totalEntries: Int,
    var entriesRead: Int,
    val boxOffset: Long,
    val boxSize: Long,
): BoxInfo(boxOffset, boxSize)

data class Mvhd(
    val timeScale: Int,
    val duration: Long
)
data class TkhdBox(
    val id:Int,
    val duration:Int,
    val width:Double,
    val height:Double
)

data class Tkhd(
    val creationTime: Long,
    val modificationTime: Long,
    val trackId: Int,
    val duration: Long
)

data class MdhdBox(
    val version: Int,
    val creationTime: Long,
    val modificationTime: Long,
    val timescale: Long,
    val duration: Long,
    val language: String
)

data class HdlrBox(
    val handlerType: String,
    val handlerName: String
)


data class VmhdBox(
    val graphicsMode: Int,
    val opColor: Triple<Int, Int, Int>
)

data class SmhdBox(
    val balance: Float
)

data class DinfBox(
    val drefCount: Int
)

// --- Root: stsd ---
data class StsdBox(
    val offset: Long,
    val size: Long,
    val entries: MutableList<SampleEntry> = mutableListOf()
)

// --- Represents one SampleEntry inside stsd ---
data class SampleEntry(
    val type: String,
    val offset: Long,
    val size: Long,
    val boxes: MutableList<Any> = mutableListOf()
)

// --- Nested box types ---
data class AvcCBox(
    val profileIndication: Int,
    val levelIndication: Int,
    val nalLengthSize: Int
)

data class BtrtBox(
    val bufferSizeDB: Int,
    val maxBitrate: Int,
    val avgBitrate: Int
)

data class PaspBox(
    val hSpacing: Int,
    val vSpacing: Int
)

data class ColrBox(
    val type: String,
    val primaries: Int? = null,
    val transfer: Int? = null,
    val matrix: Int? = null,
    val fullRange: Boolean? = null
)

data class EsdsBox(
    val tag: Int
)



class utils {
    fun writeStbl(vararg boxes: ByteArray): ByteArray {
        val totalSize = boxes.sumOf { it.size } + 8
        return ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(totalSize)
            put("stbl".toByteArray())
            boxes.forEach { put(it) }
        }.array()
    }
    fun writeStts(sampleCount: Int, sampleDelta: Int): ByteArray {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(24)                         // box size
        buffer.put("stts".toByteArray())         // box type
        buffer.putInt(0)                          // version + flags
        buffer.putInt(1)                          // entry count
        buffer.putInt(sampleCount)               // sample count
        buffer.putInt(sampleDelta)               // sample delta (duration per sample)
        return buffer.array()
    }
    fun writeStsc(samplesPerChunkList: List<Int>): ByteArray {
        val entries = mutableListOf<Triple<Int, Int, Int>>()

        var lastSamples = -1
        for ((chunkIndex, samplesPerChunk) in samplesPerChunkList.withIndex()) {
            if (samplesPerChunk != lastSamples) {
                entries.add(Triple(chunkIndex + 1, samplesPerChunk, 1))
                lastSamples = samplesPerChunk
            }
        }

        val buffer = ByteBuffer.allocate(16 + entries.size * 12).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(16 + entries.size * 12)
        buffer.put("stsc".toByteArray())
        buffer.putInt(0) // version & flags
        buffer.putInt(entries.size)

        for ((firstChunk, samplesPerChunk, descIndex) in entries) {
            buffer.putInt(firstChunk)
            buffer.putInt(samplesPerChunk)
            buffer.putInt(descIndex)
        }

        return buffer.array()
    }



    fun writeStsz(sizes: List<Int>): ByteArray {
        val entryCount = sizes.size
        val buffer = ByteBuffer.allocate(20 + 4 * entryCount).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(20 + 4 * entryCount)
        buffer.put("stsz".toByteArray())
        buffer.putInt(0)                  // version + flags
        buffer.putInt(0)                  // sample size (0 = use table)
        buffer.putInt(entryCount)
        sizes.forEach { buffer.putInt(it) }
        return buffer.array()
    }
    fun writeStco(offsets: List<Long>): ByteArray {
        val useCo64 = offsets.any { it > 0xFFFFFFFFL }
        return if (useCo64) {
            // co64 (64-bit offsets)
            val entryCount = offsets.size
            val buffer = ByteBuffer.allocate(16 + 8 * entryCount).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(16 + 8 * entryCount)
            buffer.put("co64".toByteArray())
            buffer.putInt(0) // version+flags
            buffer.putInt(entryCount)
            offsets.forEach { buffer.putLong(it) }
            buffer.array()
        } else {
            val entryCount = offsets.size
            val buffer = ByteBuffer.allocate(16 + 4 * entryCount).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(16 + 4 * entryCount)
            buffer.put("stco".toByteArray())
            buffer.putInt(0)
            buffer.putInt(entryCount)
            offsets.forEach { buffer.putInt(it.toInt()) }
            buffer.array()
        }
    }

    fun writeStss(syncSampleIndices: List<Int>): ByteArray {
        val entryCount = syncSampleIndices.size
        val buffer = ByteBuffer.allocate(16 + 4 * entryCount).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(16 + 4 * entryCount)
        buffer.put("stss".toByteArray())
        buffer.putInt(0)                          // version + flags
        buffer.putInt(entryCount)
        syncSampleIndices.forEach { buffer.putInt(it + 1) } // MP4 is 1-based
        return buffer.array()
    }
    fun writeCtts(entries: List<Pair<Int, Int>>): ByteArray {
        val entryCount = entries.size
        val buffer = ByteBuffer.allocate(16 + 8 * entryCount).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(16 + 8 * entryCount)
        buffer.put("ctts".toByteArray())
        buffer.putInt(0)                          // version + flags
        buffer.putInt(entryCount)
        entries.forEach { (count, offset) ->
            buffer.putInt(count)
            buffer.putInt(offset)
        }
        return buffer.array()
    }
    fun writeMvhd(timeScale: Int,duration: Int,nextTrack: Int): ByteArray {
        val buffer = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(108)                // box size
        buffer.put("mvhd".toByteArray()) // box type
        buffer.put(0x00)                  // version
        buffer.put(byteArrayOf(0, 0, 0))  // flags

        val macTime = ((System.currentTimeMillis() / 1000) + 2082844800L).toInt()
        buffer.putInt(macTime)           // creation_time
        buffer.putInt(macTime)           // modification_time
        buffer.putInt(timeScale)         // timescale
        buffer.putInt(duration)  // duration

        buffer.putInt(0x00010000)        // rate = 1.0
        buffer.putShort(0x0100.toShort())// volume = 1.0
        buffer.putShort(0)               // reserved
        buffer.putInt(0)                 // reserved
        buffer.putInt(0)

        // Identity matrix
        val matrix = listOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        matrix.forEach { buffer.putInt(it) }

        repeat(6) { buffer.putInt(0) }   // pre_defined
        buffer.putInt(nextTrack)                 // next_track_ID

        return buffer.array()
    }
    fun writeTrak(tkhd: ByteArray, mdia: ByteArray): ByteArray {
        val content = tkhd + mdia
        val box = ByteBuffer.allocate(8 + content.size).order(ByteOrder.BIG_ENDIAN)
        box.putInt(8 + content.size)
        box.put("trak".toByteArray())
        box.put(content)
        return box.array()
    }

    fun writeTkhd(trackId: Int, duration: Int, widthPixels: Int = 0, heightPixels: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(92)
        buffer.put("tkhd".toByteArray(Charsets.US_ASCII))
        buffer.put(0x00) // version
        buffer.put(byteArrayOf(0x00, 0x00, 0x07)) // flags (enabled | in movie | in preview)

        val macTime = ((System.currentTimeMillis() / 1000) + 2082844800L).toInt()
        buffer.putInt(macTime)           // creation_time
        buffer.putInt(macTime)           // modification_time
        buffer.putInt(trackId)           // track_ID  (use unique ID)
        buffer.putInt(0)                 // reserved
        buffer.putInt(duration)          // duration
        buffer.putInt(0)                 // reserved
        buffer.putInt(0)

        buffer.putShort(0)               // layer
        buffer.putShort(0)               // alternate group
        buffer.putShort(0x0100.toShort())// volume (1.0) (keep 0 for pure video)
        buffer.putShort(0)               // reserved

        // Unity matrix (9 * 4 bytes)
        val matrix = listOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        matrix.forEach { buffer.putInt(it) }

        // width & height are 16.16 fixed point
        buffer.putInt(widthPixels shl 16)
        buffer.putInt(heightPixels shl 16)

        return buffer.array()
    }


    fun writeMdhd(timeScale: Int, duration: Int): ByteArray {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(32)
        buffer.put("mdhd".toByteArray())
        buffer.put(0x00) // version
        buffer.put(byteArrayOf(0, 0, 0))

        val macTime = ((System.currentTimeMillis() / 1000) + 2082844800L).toInt()
        buffer.putInt(macTime)
        buffer.putInt(macTime)
        buffer.putInt(timeScale)
        buffer.putInt(duration)
        buffer.putShort(0x55c4) // language code (und)
        buffer.putShort(0)      // pre-defined

        return buffer.array()
    }

    fun writeHdlr(handlerType: String): ByteArray {
        val name = when (handlerType) {
            "vide" -> "VideoHandler"
            "soun" -> "SoundHandler"
            else -> "UnknownHandler"
        }

        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size + 1 // null-terminator

        val size = 8 + 4 + 4 + 4 + 4 + 4 + 4 + nameLength
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(size)
        buffer.put("hdlr".toByteArray())         // 4 bytes
        buffer.put(0x00)                         // version
        buffer.put(byteArrayOf(0, 0, 0))         // flags
        buffer.putInt(0)                         // pre_defined
        buffer.put(handlerType.toByteArray(Charsets.US_ASCII).copyOf(4)) // handler_type
        buffer.putInt(0)                         // reserved
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(nameBytes)
        buffer.put(0)                            // null-terminator

        return buffer.array()
    }

    fun writeMdia(mdhd: ByteArray, hdlr: ByteArray,minf: ByteArray): ByteArray {
        val content = mdhd + hdlr+minf
        val box = ByteBuffer.allocate(8 + content.size).order(ByteOrder.BIG_ENDIAN)
        box.putInt(8 + content.size)
        box.put("mdia".toByteArray())
        box.put(content)
        return box.array()
    }
    fun writeMinf(vmhdOrSmhd: ByteArray, dinfBox: ByteArray, stblBox: ByteArray): ByteArray {
        val minfSize = 8 + vmhdOrSmhd.size + dinfBox.size + stblBox.size
        return ByteBuffer.allocate(minfSize).apply {
            putInt(minfSize)
            put("minf".toByteArray())
            put(vmhdOrSmhd)
            put(dinfBox)
            put(stblBox)
        }.array()
    }
    fun writeSmhd(): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(16)                       // size
        buffer.put("smhd".toByteArray())        // box type
        buffer.putInt(0)                        // version + flags
        buffer.putShort(0)                      // balance
        buffer.putShort(0)                      // reserved
        return buffer.array()
    }
    fun writeVmhd(): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(20)                       // size
        buffer.put("vmhd".toByteArray())        // box type
        buffer.putInt(0x00000001)               // version(0) + flags(1) (graphics mode present)
        buffer.putShort(0)                      // graphicsmode
        buffer.putShort(0)                      // opcolor R
        buffer.putShort(0)                      // opcolor G
        buffer.putShort(0)                      // opcolor B
        return buffer.array()
    }

    fun writeMoov(mvhd: ByteArray, tracks: MutableList<ByteArray>): ByteArray {
        val totalSize = 8 + mvhd.size + tracks.sumOf { it.size }
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(totalSize)
            put("moov".toByteArray())
            put(mvhd)
            tracks.forEach { put(it) }
        }.array()
    }
    fun writeFtyp(overrideMainBrand: String? = null): ByteArray {
        // Define compatible brands (can be adjusted as needed)
        val compatibleBrands = mutableListOf("mp41", "isom", "iso2")

        // If override brand exists, include mp42 as compatible
        if (overrideMainBrand != null) {
            compatibleBrands.add("mp42")
        }

        // Calculate total size
        val size = 16 + compatibleBrands.size * 4 +
                if (overrideMainBrand != null) 4 else 0

        return ByteBuffer.allocate(size).apply {
            putInt(size)
            put("ftyp".toByteArray(Charsets.US_ASCII))

            if (overrideMainBrand == null) {
                put("mp42".toByteArray(Charsets.US_ASCII)) // major brand
                putInt(0x200) // minor version (512)
            } else {
                put(overrideMainBrand.toByteArray(Charsets.US_ASCII))
                putInt(0)
                // Extra brand for mp42 compatibility
                put("mp42".toByteArray(Charsets.US_ASCII))
            }

            // Write compatible brands
            compatibleBrands.forEach {
                put(it.toByteArray(Charsets.US_ASCII))
            }
        }.array()
    }



}
fun estimateMoovSize(sampleCount: Int): Int {
    val baseMoov = 1024   // mvhd + trak headers
    val perSample = 8     // stsz, stts, ctts, stco, stsc, stss averages
    val estimatedSize = baseMoov + (sampleCount * perSample)

    // Round up to nearest 4KB
    return ((estimatedSize + 4095) / 4096) * 4096
}







fun extractSpsPpsFromStsd(stsdBox: ByteArray): Pair<ByteArray, ByteArray>? {
    val buf = ByteBuffer.wrap(stsdBox).order(ByteOrder.BIG_ENDIAN)

    // Skip FullBox header (version + flags) = 4 bytes
    // Then skip entry_count = 4 bytes
    if (buf.remaining() < 8) return null
    buf.position(16)

    while (buf.remaining() >= 8) {
        val boxStart = buf.position()
        val size = buf.int
        val typeBytes = ByteArray(4)
        buf.get(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)

        if (size < 8 || boxStart + size > buf.limit()) {
            println("⚠ Invalid box in stsd: type=$type size=$size — stopping")
            break
        }

        println("Found in stsd → type=$type, size=$size")

        if (type == "avc1" || type == "encv") {
            // Jump into avc1 content
            val innerOffset = boxStart + 8 + 78 // skip fixed video sample entry header
            val remaining = size - 8 - 78
            if (remaining <= 0 || innerOffset + remaining > buf.limit()) {
                println("⚠ avc1 inner box out of range")
                break
            }

            val sub = ByteArray(remaining)
            val oldPos = buf.position()
            buf.position(innerOffset)
            buf.get(sub)
            buf.position(oldPos)

            return extractSpsPpsFromAvc1(sub)
        }

        buf.position(boxStart + size)
    }

    return null
}

fun parseTfhd(reader: RandomAccessFile, offset: Long,mdatOffset: Long): TfhdBox {
    reader.seek(offset + 8) // skip header ('size' + 'type')
    val fullBoxHeader = ByteArray(4)
    reader.readFully(fullBoxHeader)

    val version = fullBoxHeader[0].toInt() and 0xFF
    val flags = ((fullBoxHeader[1].toInt() and 0xFF) shl 16) or
            ((fullBoxHeader[2].toInt() and 0xFF) shl 8) or
            (fullBoxHeader[3].toInt() and 0xFF)

    val trackId = reader.readInt()
    // Optional fields per ISO/IEC 14496-12 §8.8.7
    var baseDataOffset=mdatOffset
    var sampleDescriptionIndex: Int? = null
    var defaultSampleDuration: Int? = null
    var defaultSampleSize: Int? = null
    var defaultSampleFlags: Int? = null

    if ((flags and 0x000001) != 0) { // base-data-offset-present
        baseDataOffset = reader.readLong()
    }
    if ((flags and 0x000002) != 0) { // sample-description-index-present
        sampleDescriptionIndex = reader.readInt()
    }
    if ((flags and 0x000008) != 0) { // default-sample-duration-present
        defaultSampleDuration = reader.readInt()
    }
    if ((flags and 0x000010) != 0) { // default-sample-size-present
        defaultSampleSize = reader.readInt()
    }
    if ((flags and 0x000020) != 0) { // default-sample-flags-present
        defaultSampleFlags = reader.readInt()
    }
    return TfhdBox(
        version = version,
        flags = flags,
        trackId = trackId,
        baseDataOffset = baseDataOffset,
        sampleDescriptionIndex = sampleDescriptionIndex,
        defaultSampleDuration = defaultSampleDuration,
        defaultSampleSize = defaultSampleSize,
        defaultSampleFlags = defaultSampleFlags
    )
}

 fun parseTfdt(reader: RandomAccessFile, offset: Long, size: Long): TfdtBox {
    reader.seek(offset + 8) // skip header
    val fullBoxHeader = ByteArray(4)
    reader.readFully(fullBoxHeader)

    val version = fullBoxHeader[0].toInt() and 0xFF
    val flags = ((fullBoxHeader[1].toInt() and 0xFF) shl 16) or
            ((fullBoxHeader[2].toInt() and 0xFF) shl 8) or
            (fullBoxHeader[3].toInt() and 0xFF)

    val baseDecodeTime = when (version) {
        1 -> reader.readLong()
        else -> reader.readInt().toLong()
    }
    return TfdtBox(
        version = version,
        flags = flags,
        baseMediaDecodeTime = baseDecodeTime,
        )
}

fun countEntriesInMoof(reader: RandomAccessFile, moofOffset: Long, moofSize: Long): Int {
    var totalTrunEntries = 0L
    var offset = moofOffset
    val moofEnd = moofOffset + moofSize

    while (offset + 8 <= moofEnd) {
        reader.seek(offset)
        val size = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes, Charsets.US_ASCII)
        val nextBox = offset + size

        if (type == "traf") {
            var innerOffset = offset + 8
            val trafEnd = offset + size

            while (innerOffset + 8 <= trafEnd) {
                reader.seek(innerOffset)
                val subSize = reader.readInt().toLong()
                val subTypeBytes = ByteArray(4).also { reader.readFully(it) }
                val subType = String(subTypeBytes, Charsets.US_ASCII)
                val subBoxEnd = innerOffset + subSize

                if (subType == "trun" && subSize >= 12) {
                    // Skip version and flags
                    reader.skipBytes(4)
                    val entryCount = reader.readInt()
                    totalTrunEntries += entryCount
                }

                innerOffset = subBoxEnd
            }
        }

        offset = nextBox
    }

    return totalTrunEntries.toInt()
}

//==========these will be used to parse file============
fun parseMvhd(reader: RandomAccessFile,offset: Long): Mvhd {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags

    if (version == 1) {
        reader.skipBytes(8 + 8) // creation + modification
        val timescale = reader.readInt()
        val duration = reader.readLong()
        return Mvhd(
            timescale,
            duration
        )
    } else {
        reader.skipBytes(4 + 4) // creation + modification
        val timescale = reader.readInt()
        val duration = reader.readInt()
        return Mvhd(
            timescale,
            duration.toLong()
        )
    }
}
fun parseTkhd(reader: RandomAccessFile, offset: Long): Tkhd {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags

    val creationTime: Long
    val modificationTime: Long
    val trackId: Int
    val duration: Long

    if (version == 1) {
        creationTime = reader.readLong()
        modificationTime = reader.readLong()
        trackId = reader.readInt()
        reader.skipBytes(4) // reserved
        duration = reader.readLong()
    } else {
        creationTime = reader.readInt().toLong() and 0xFFFFFFFFL
        modificationTime = reader.readInt().toLong() and 0xFFFFFFFFL
        trackId = reader.readInt()
        reader.skipBytes(4) // reserved
        duration = reader.readInt().toLong() and 0xFFFFFFFFL
    }

    reader.skipBytes(8) // reserved
    val layer = reader.readShort()
    val alternateGroup = reader.readShort()
    val volume = reader.readShort()
    reader.skipBytes(2) // reserved

    // Transformation matrix (3x3) — 9 * 4 bytes
    val matrix = IntArray(9) { reader.readInt() }

    // Track width/height in 16.16 fixed point
    val width = reader.readInt() / 65536.0
    val height = reader.readInt() / 65536.0
    return Tkhd(
        creationTime = creationTime,
        modificationTime = modificationTime,
        trackId = trackId,
        duration = duration
    )
}

fun parseEdts(reader: RandomAccessFile, doLogging: Boolean, offset: Long, size: Long) {
    val edtsEnd = offset + size
    reader.seek(offset)
    while (reader.filePointer + 8 <= edtsEnd) {
        val boxStart = reader.filePointer
        val boxSize = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes, Charsets.US_ASCII)
        val payloadOffset = boxStart + 8
        val payloadSize = boxSize - 8
        if (boxSize < 8 || boxStart + boxSize > edtsEnd) break
        if (doLogging){
            println(
                "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                        "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
            )
        }

        when (type) {
            "elst" -> {
                parseElst(reader,payloadOffset, payloadSize)
                reader.seek(payloadOffset+payloadSize)
            }
            else -> reader.seek(payloadOffset + payloadSize)
        }
    }

    reader.seek(edtsEnd)
}

fun parseElst(reader: RandomAccessFile,offset: Long, size: Long) {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags
    val entryCount = reader.readInt()

    for (i in 0 until entryCount) {
        val segmentDuration: Long
        val mediaTime: Long
        val mediaRate: Double

        if (version == 1) {
            segmentDuration = reader.readLong()
            mediaTime = reader.readLong()
        } else {
            segmentDuration = reader.readInt().toLong() and 0xFFFFFFFFL
            mediaTime = reader.readInt().toLong() and 0xFFFFFFFFL
        }

        val mediaRateInteger = reader.readShort()
        val mediaRateFraction = reader.readShort()
        mediaRate = mediaRateInteger + mediaRateFraction / 65536.0

    }
}

fun parseMdhd(reader: RandomAccessFile,offset: Long): MdhdBox {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags

    val creationTime: Long
    val modificationTime: Long
    val timescale: Long
    val duration: Long

    if (version == 1) {
        creationTime = reader.readLong()
        modificationTime = reader.readLong()
        timescale = reader.readInt().toLong() and 0xFFFFFFFFL
        duration = reader.readLong()
    } else {
        creationTime = reader.readInt().toLong() and 0xFFFFFFFFL
        modificationTime = reader.readInt().toLong() and 0xFFFFFFFFL
        timescale = reader.readInt().toLong() and 0xFFFFFFFFL
        duration = reader.readInt().toLong() and 0xFFFFFFFFL
    }

    val langBytes = reader.readShort().toInt()
    val language = buildString {
        append(((langBytes shr 10) and 0x1F) + 0x60)
        append(((langBytes shr 5) and 0x1F) + 0x60)
        append((langBytes and 0x1F) + 0x60)
    }.map { it.toChar() }.joinToString("")

    reader.skipBytes(2) // pre-defined
    return MdhdBox(
        version = version,
        creationTime = creationTime,
        modificationTime = modificationTime,
        timescale = timescale,
        duration = duration,
        language = language
    )

}

fun parseHdlr(reader: RandomAccessFile,offset: Long, size: Long): HdlrBox {
    reader.seek(offset)
    reader.skipBytes(4) // pre-defined (0)
    val handlerTypeBytes = ByteArray(4)
    reader.readFully(handlerTypeBytes)
    val handlerType = String(handlerTypeBytes, Charsets.US_ASCII)

    reader.skipBytes(12) // reserved
    val nameLength = (size - (reader.filePointer - offset)).toInt()
    val nameBytes = ByteArray(nameLength.coerceAtLeast(0))
    reader.readFully(nameBytes)
    val handlerName = String(nameBytes).trimEnd('\u0000')
    return HdlrBox(
        handlerType = handlerType,
        handlerName = handlerName
    )

}

fun parseVmhd(reader: RandomAccessFile, offset: Long): VmhdBox {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags (should be 0x000001 for 'vmhd')

    val graphicsMode = reader.readUnsignedShort()
    val opColorR = reader.readUnsignedShort()
    val opColorG = reader.readUnsignedShort()
    val opColorB = reader.readUnsignedShort()

    return VmhdBox(
        graphicsMode = graphicsMode,
        opColor = Triple(opColorR, opColorG, opColorB)
    )
}

fun parseSmhd(reader: RandomAccessFile, offset: Long): SmhdBox {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags

    val balanceFixed16 = reader.readShort().toInt()
    reader.skipBytes(2) // reserved

    val balance = balanceFixed16 / 256f // convert 8.8 fixed-point to float

    return SmhdBox(balance = balance)
}

fun parseDinf(reader: RandomAccessFile, offset: Long, size: Long): DinfBox {
    val dinfEnd = offset + size
    reader.seek(offset)

    var drefCount = 0

    while (reader.filePointer + 8 <= dinfEnd) {
        val boxStart = reader.filePointer
        val boxSize = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes, Charsets.US_ASCII)
        val payloadOffset = reader.filePointer
        val payloadSize = boxSize - 8

        if (type == "dref") {
            reader.skipBytes(4) // version + flags
            drefCount = reader.readInt()
            // skip rest of entries (we ignore URLs/URNs for now)
        }

        reader.seek(boxStart + boxSize)
    }

    return DinfBox(drefCount = drefCount)
}


fun parseAvcC(reader: RandomAccessFile,offset: Long): AvcCBox {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    val profile = reader.readUnsignedByte()
    val compat = reader.readUnsignedByte()
    val level = reader.readUnsignedByte()
    val lengthSize = reader.readUnsignedByte() and 0x3
    return AvcCBox(profile, level, lengthSize + 1)
}

fun parseBtrt(reader: RandomAccessFile,offset: Long): BtrtBox {
    reader.seek(offset)
    val buffer = reader.readInt()
    val max = reader.readInt()
    val avg = reader.readInt()
    return BtrtBox(buffer, max, avg)
}

fun parsePasp(reader: RandomAccessFile,offset: Long): PaspBox {
    reader.seek(offset)
    val h = reader.readInt()
    val v = reader.readInt()
    return PaspBox(h, v)
}

fun parseColr(reader: RandomAccessFile,offset: Long, size: Long): ColrBox {
    reader.seek(offset)
    val typeBytes = ByteArray(4).also { reader.readFully(it) }
    val type = String(typeBytes)
    return if (type == "nclx" && size >= 11) {
        val prim = reader.readUnsignedShort()
        val trans = reader.readUnsignedShort()
        val matrix = reader.readUnsignedShort()
        val full = reader.readUnsignedByte() and 0x80 != 0
        ColrBox(type, prim, trans, matrix, full)
    } else {
        ColrBox(type)
    }
}

fun parseEsds(reader: RandomAccessFile,offset: Long): EsdsBox {
    reader.seek(offset)
    reader.skipBytes(4)
    val tag = reader.readUnsignedByte()
    return EsdsBox(tag)
}

fun RandomAccessFile.readUInt8(): Int =
    readUnsignedByte()

fun RandomAccessFile.readUInt32(): Long {
    val b1 = this.readUnsignedByte().toLong()
    val b2 = this.readUnsignedByte().toLong()
    val b3 = this.readUnsignedByte().toLong()
    val b4 = this.readUnsignedByte().toLong()
    return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
}

private fun extractSpsPpsFromAvc1(avc1Data: ByteArray): Pair<ByteArray, ByteArray>? {
    val buf = ByteBuffer.wrap(avc1Data).order(ByteOrder.BIG_ENDIAN)

    while (buf.remaining() >= 8) {
        val start = buf.position()
        val size = buf.int
        val typeBytes = ByteArray(4)
        buf.get(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)

        if (size < 8 || start + size > buf.limit()) {
            println("⚠ Invalid sub-box in avc1: type=$type size=$size — stopping")
            break
        }

        println("Found in avc1 → $type (size=$size)")

        if (type == "avcC") {
            val avcCData = ByteArray(size - 8)
            buf.get(avcCData)
            return parseAvcC(avcCData)
        } else {
            buf.position(start + size)
        }
    }

    return null
}

private fun parseAvcC(avcCData: ByteArray): Pair<ByteArray, ByteArray>? {
    val avcBuf = ByteBuffer.wrap(avcCData).order(ByteOrder.BIG_ENDIAN)
    if (avcBuf.remaining() < 6) return null

    avcBuf.position(5) // skip version + profile + level + lengthSizeMinusOne

    val numOfSPS = avcBuf.get().toInt() and 0x1F
    if (numOfSPS < 1) return null

    val spsLength = avcBuf.short.toInt() and 0xFFFF
    val sps = ByteArray(spsLength)
    avcBuf.get(sps)

    val numOfPPS = avcBuf.get().toInt() and 0xFF
    if (numOfPPS < 1) return Pair(sps, ByteArray(0))

    val ppsLength = avcBuf.short.toInt() and 0xFFFF
    val pps = ByteArray(ppsLength)
    avcBuf.get(pps)

    println("✅ Parsed avcC → SPS=${sps.size} bytes, PPS=${pps.size} bytes")
    return Pair(sps, pps)
}

fun saveH264Frame(
    frame: ByteArray,
    outputDir: File = File(System.getProperty("java.io.tmpdir")),
    fileName: String = "frame.h264",
    stsdBox: ByteArray
): File {
    val (resolvedSps, resolvedPps) = extractSpsPpsFromStsd(stsdBox) ?: error("Failed to parse SPS/PPS from stsdBox")

    // convert length-prefixed NALs to start code prefixed
    fun convertToAnnexB(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() > 4) {
            val len = buf.int
            if (len <= 0 || len > buf.remaining()) break
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            val nal = ByteArray(len)
            buf.get(nal)
            out.write(nal)
        }
        return out.toByteArray()
    }

    val convertedFrame = convertToAnnexB(frame)

    if (!outputDir.exists()) outputDir.mkdirs()
    val outputFile = File(outputDir, fileName)

    FileOutputStream(outputFile).use { fos ->
        fos.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        fos.write(resolvedSps)
        fos.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        fos.write(resolvedPps)
        fos.write(convertedFrame)
    }

    return outputFile
}


/*ffprobe -v error -show_format -show_streams output.mp4
*/



/*fun parseTkhd(reader: RandomAccessFile, offset: Long): TkhdBox {
    reader.seek(offset)
    val version = reader.readUnsignedByte()
    reader.skipBytes(3) // flags
    val creationTime = reader.readInt()
    val modificationTime = reader.readInt()
    val trackId = reader.readInt()
    reader.skipBytes(4) // reserved
    val duration = reader.readInt()
    reader.skipBytes(8) // reserved
    val layer = reader.readShort()
    val alternateGroup = reader.readShort()
    val volume = reader.readShort()
    reader.skipBytes(2) // reserved
    reader.skipBytes(36) // matrix
    val width = reader.readInt() / 65536.0
    val height = reader.readInt() / 65536.0

    return TkhdBox(trackId, duration, width, height)
}*/












