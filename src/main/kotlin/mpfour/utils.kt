package mpfour

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder


data class TfhdBox(
    val version: Int,
    val flags: Int,
    val trackId: Long,

    val baseDataOffset: Long? = null,
    val sampleDescriptionIndex: Int? = null,
    val defaultSampleDuration: Int? = null,
    val defaultSampleSize: Int? = null,
    val defaultSampleFlags: Int? = null,

    val boxOffset: Long,
    val boxSize: Long
) : BoxInfo(boxOffset, boxSize)

data class TrunBox(
    val version: Int,
    val flags: Int,
    
  
    val dataOffset: Int? = null,
    val firstSampleFlags: Int? = null,

    // Instead of storing all samples, store offsets & size to read on demand
    var entriesOffset: Long,  // where entries start (after header)
    val trunEndOffset: Long,    // total size of entries section

    val boxOffset: Long,
    val boxSize: Long,
) : BoxInfo(boxOffset, boxSize)


data class TfdtBox(
    val version: Int,
    val flags: Int,
    val baseMediaDecodeTime: Long,

    val boxOffset: Long,
    val boxSize: Long,
) : BoxInfo(boxOffset, boxSize)

data class TrafBox(
    val offset: Long,
    val size: Long,
    val tfhd: TfhdBox?,
    val tfdt: TfdtBox?,
    val truns: List<TrunBox>
)

data class TrunResult(
    val samples: MutableList<TrunSampleEntry>,
    val lastEntryOffset: Long
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
        val entryCount = offsets.size
        val buffer = ByteBuffer.allocate(16 + 4 * entryCount).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(16 + 4 * entryCount)
        buffer.put("stco".toByteArray())
        buffer.putInt(0)                         // version + flags
        buffer.putInt(entryCount)
        offsets.forEach { buffer.putInt(it.toInt()) } // Use `.toInt()` if offsets are within 32-bit
        return buffer.array()
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
    fun writeTkhd(duration: Int): ByteArray {
        val buffer = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(92)                    // size
        buffer.put("tkhd".toByteArray())    // type
        buffer.put(0x00)                    // version
        buffer.put(byteArrayOf(0x00, 0x00, 0x07)) // flags (enabled | in movie | in preview)

        val macTime = ((System.currentTimeMillis() / 1000) + 2082844800L).toInt()
        buffer.putInt(macTime)              // creation_time
        buffer.putInt(macTime)              // modification_time
        buffer.putInt(1)              // track_ID
        buffer.putInt(0)                    // reserved
        buffer.putInt(duration)             // duration
        buffer.putInt(0)                    // reserved
        buffer.putInt(0)                    // reserved

        buffer.putShort(0)                  // layer
        buffer.putShort(0)                  // alternate group
        buffer.putShort(0x0100)             // volume (0 for video)
        buffer.putShort(0)                  // reserved

        val matrix = listOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        matrix.forEach { buffer.putInt(it) }

        buffer.putInt(0)                    // width (placeholder)
        buffer.putInt(0)                    // height (placeholder)

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
    fun writeVmhd(handlerType: String): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(20) // size
        buffer.put(handlerType.toByteArray())
        buffer.putInt(0x00000001) // version(1) + flags (graphics mode present)
        buffer.putShort(0) // graphicsmode
        buffer.putShort(0) // opcolor R
        buffer.putShort(0) // opcolor G
        buffer.putShort(0) // opcolor B
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
    fun writeFtyp(): ByteArray {
        val brands = arrayOf("isom", "iso2", "mp41", "mp42")
        val size = 8 + 4 + 4 + brands.size * 4  // header + major_brand + minor_version + compatible brands

        return ByteBuffer.allocate(size).apply {
            putInt(size)                         // size
            put("ftyp".toByteArray())            // type
            put("isom".toByteArray())            // major_brand
            putInt(0x200)                        // minor_version
            brands.forEach { put(it.toByteArray()) }  // compatible brands
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



fun parseTraf(reader: RandomAccessFile, moofOffset: Long, moofSize: Long): List<TrafBox> {
    val trafBoxes = mutableListOf<TrafBox>()

    var innerOffset = moofOffset
    val moofEnd = moofOffset + moofSize

    while (innerOffset + 8 <= moofEnd) {
        reader.seek(innerOffset)
        val header = ByteArray(8)
        reader.readFully(header)

        val size = ByteBuffer.wrap(header, 0, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int.toLong()

        val type = String(header, 4, 4, Charsets.US_ASCII)

        // Sanity check: avoid infinite loop on invalid boxes
        if (size <= 0 || innerOffset + size > moofEnd) break

        when (type) {
            "traf" -> {
                println("Found traf at offset=$innerOffset size=$size")

                val traf = parseTrafBoxes(reader, innerOffset + 8, size - 8)
                trafBoxes.add(traf)
            }

            else -> {
                // Skip unknown boxes inside moof
            }
        }

        innerOffset += size
    }

    return trafBoxes
}

fun parseTrafBoxes(reader: RandomAccessFile, trafOffset: Long, trafSize: Long): TrafBox {
    var innerOffset = trafOffset
    val trafEnd = trafOffset + trafSize

    var tfhdBox: TfhdBox? = null
    var tfdtBox: TfdtBox? = null
    val trunBoxes = mutableListOf<TrunBox>()

    while (innerOffset + 8 <= trafEnd) {
        reader.seek(innerOffset)
        val header = ByteArray(8)
        reader.readFully(header)

        val size = ByteBuffer.wrap(header, 0, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int.toLong()
        val type = String(header, 4, 4, Charsets.US_ASCII)

        // safety: if size=0 or invalid, stop
        if (size <= 0 || innerOffset + size > trafEnd) break

        when (type) {
            "tfhd" -> {
                tfhdBox = parseTfhd(reader, innerOffset + 8, size - 8)
            }
            "tfdt" -> {
                tfdtBox = parseTfdt(reader, innerOffset + 8, size - 8)
            }
            "trun" -> {
                val trun = parseTrun(reader, innerOffset + 8, size - 8)
                trunBoxes.add(trun)
            }
            else -> {
                // Unhandled sub-box; skip it
            }
        }

        innerOffset += size
    }

    return TrafBox(
        offset = trafOffset - 8, // include box header
        size = trafSize + 8,
        tfhd = tfhdBox,
        tfdt = tfdtBox,
        truns = trunBoxes
    )
}

fun RandomAccessFile.readUInt32(): Long =
    readInt().toLong() and 0xFFFFFFFFL

fun RandomAccessFile.readUInt8(): Int =
    readUnsignedByte()


fun parseTfhd(reader: RandomAccessFile, offset: Long, size: Long): TfhdBox {
    reader.seek(offset)

    val version = reader.readUInt8()
    val flags = (reader.readUInt8() shl 16) or
            (reader.readUInt8() shl 8) or
            reader.readUInt8()

    val trackId = reader.readUInt32()

    var baseDataOffset: Long? = null
    var sampleDescriptionIndex: Int? = null
    var defaultSampleDuration: Int? = null
    var defaultSampleSize: Int? = null
    var defaultSampleFlags: Int? = null

    if (flags and 0x000001 != 0) baseDataOffset = reader.readLong()
    if (flags and 0x000002 != 0) sampleDescriptionIndex = reader.readInt()
    if (flags and 0x000008 != 0) defaultSampleDuration = reader.readInt()
    if (flags and 0x000010 != 0) defaultSampleSize = reader.readInt()
    if (flags and 0x000020 != 0) defaultSampleFlags = reader.readInt()

    return TfhdBox(
        version, flags, trackId,
        baseDataOffset, sampleDescriptionIndex,
        defaultSampleDuration, defaultSampleSize, defaultSampleFlags,
        boxOffset = offset - 8, // include header
        boxSize = size + 8
    )
}


fun parseTfdt(reader: RandomAccessFile, offset: Long, size: Long): TfdtBox {
    reader.seek(offset)

    val version = reader.readUInt8()
    val flags = (reader.readUInt8() shl 16) or
            (reader.readUInt8() shl 8) or
            reader.readUInt8()

    val baseMediaDecodeTime =
        if (version == 1) reader.readLong()
        else reader.readUInt32()

    return TfdtBox(
        version, flags, baseMediaDecodeTime,
        boxOffset = offset - 8,
        boxSize = size + 8
    )
}

fun parseTrun(reader: RandomAccessFile, offset: Long, size: Long): TrunBox {
    reader.seek(offset)

    val version = reader.readUInt8()
    val flags = (reader.readUInt8() shl 16) or
            (reader.readUInt8() shl 8) or
            reader.readUInt8()

    val sampleCount = reader.readInt()

    var dataOffset: Int? = null
    var firstSampleFlags: Int? = null

    // Flags define optional fields
    if (flags and 0x000001 != 0) dataOffset = reader.readInt()
    if (flags and 0x000004 != 0) firstSampleFlags = reader.readInt()

    val entriesStart = reader.filePointer
    val entriesLength = size - (entriesStart - offset)

    // We don't parse all sample entries here (to save memory)
    // You can later seek to entriesOffset to read samples as needed

    return TrunBox(
        version = version,
        flags =flags,
        dataOffset = dataOffset,
        firstSampleFlags =firstSampleFlags,
        entriesOffset = entriesStart,
        trunEndOffset = entriesStart + entriesLength,
        boxOffset = offset,
        boxSize = size
    )
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














