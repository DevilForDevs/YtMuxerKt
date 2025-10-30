package mpfour

import java.io.RandomAccessFile
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder


/*offset: Long,
    size: Long,
    requiredEntries: Int,
    lastEntryCtoEndOffset: Long,
    firstSampleOffset: Long // Absolute offset where mdat payload starts*/

data class TrunParsedResult(
    val samples: List<TrunSampleEntry>,
    val lastEntryCtoEndOffset: Long,
    val totalSampleCount: Int
)

data class LastSampleEntryInfo(
   var parsedHeader: Boolean,
    var version:Int,
    var flags:Int,

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



fun parseTraf(reader: RandomAccessFile, moofOffset: Long, moofSize: Long,lastRetivedSampleInfo: LastSampleEntryInfo) {
    var innerOffset = moofOffset
    val moofEnd = moofOffset + moofSize

    while (innerOffset + 8 <= moofEnd) {
        reader.seek(innerOffset)
        val header = ByteArray(8)
        reader.readFully(header)

        val size = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
        val type = String(header, 4, 4, Charsets.US_ASCII)

        when (type) {
            "traf" -> {
                println("Found traf at offset=$innerOffset size=$size")
                parseTrafBoxes(reader, innerOffset + 8, size - 8,lastRetivedSampleInfo)
            }
        }

        if (size <= 0) break
        innerOffset += size
    }
}

fun parseTrafBoxes(reader: RandomAccessFile, trafOffset: Long, trafSize: Long,lastRetivedSampleInfo: LastSampleEntryInfo) {
    var innerOffset = trafOffset
    val trafEnd = trafOffset + trafSize

    while (innerOffset + 8 <= trafEnd) {
        reader.seek(innerOffset)
        val header = ByteArray(8)
        reader.readFully(header)

        val size = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
        val type = String(header, 4, 4, Charsets.US_ASCII)

        when (type) {
            "tfhd" -> parseTfhd(reader, innerOffset + 8, size - 8)
            "tfdt" -> parseTfdt(reader, innerOffset + 8, size - 8)
            "trun" -> parseTrun(reader = reader,innerOffset + 8)
        }

        if (size <= 0) break
        innerOffset += size
    }
}

/*reader, innerOffset + 8, size - 8,-1,3,firstSampleOffset*/

fun parseTfhd(reader: RandomAccessFile, offset: Long, size: Long) {
    reader.seek(offset)
    val data = ByteArray(size.toInt())
    reader.readFully(data)
    println("Parsed tfhd (${size} bytes)")
}

fun parseTfdt(reader: RandomAccessFile, offset: Long, size: Long) {
    reader.seek(offset)
    val data = ByteArray(size.toInt())
    reader.readFully(data)
    println("Parsed tfdt (${size} bytes)")
}



fun parseTrun(
    reader: RandomAccessFile,
    offset: Long
) {
    reader.seek(offset)

    val version = reader.readUnsignedByte()
    val flags = (reader.readUnsignedByte() shl 16) or
            (reader.readUnsignedByte() shl 8) or
            reader.readUnsignedByte()

    val sampleCount = reader.readInt()

    println("trun: version=$version flags=${flags.toString(16)} sampleCount=$sampleCount")

    var dataOffset: Int? = null
    var firstSampleFlags: Int? = null

    if (flags and 0x000001 != 0) { // data-offset-present
        dataOffset = reader.readInt()
        println("  dataOffset=$dataOffset")
    }

    if (flags and 0x000004 != 0) { // first-sample-flags-present
        firstSampleFlags = reader.readInt()
        println("  firstSampleFlags=0x${firstSampleFlags.toString(16)}")
    }

    val hasSampleDuration = (flags and 0x000100) != 0
    val hasSampleSize = (flags and 0x000200) != 0
    val hasSampleFlags = (flags and 0x000400) != 0
    val hasSampleCompositionTimeOffset = (flags and 0x000800) != 0

    println("  hasDuration=$hasSampleDuration hasSize=$hasSampleSize hasFlags=$hasSampleFlags hasCTO=$hasSampleCompositionTimeOffset")

    for (i in 0 until sampleCount) {
        val entry = mutableListOf<String>()
        if (hasSampleDuration) {
            entry += "duration=${reader.readInt()}"
        }
        if (hasSampleSize) {
            entry += "size=${reader.readInt()}"
        }
        if (hasSampleFlags) {
            entry += "flags=0x${reader.readInt().toString(16)}"
        }
        if (hasSampleCompositionTimeOffset) {
            if (version == 0) {
                entry += "cto=${reader.readInt()}"
            } else {
                entry += "cto=${reader.readInt()}" // could be signed in version 1
            }
        }
        println("  sample[$i]: ${entry.joinToString(", ")}")
    }
}










/* #0 size=59217 flags=0 cto=512
1008
  #1 size=2706 flags=10000 cto=1536
1020
  #2 size=546 flags=10000 cto=0
1032*/

/*for (i in 0 until sampleCount) {
        val parts = mutableListOf<String>()
        if (hasDuration) parts += "duration=${reader.readInt()}"
        if (hasSize) parts += "size=${reader.readInt()}"
        if (hasFlags) parts += "flags=${reader.readInt().toUInt().toString(16)}"
        if (hasCTO) parts += "cto=${reader.readInt()}"
        if (i==10){

            break
        }
        println("  #$i ${parts.joinToString(" ")}")
    }*/






