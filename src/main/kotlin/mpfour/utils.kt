package org.ytmuxer.mpfour

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        return ByteBuffer.allocate(28).apply {
            putInt(28)                     // size
            put("ftyp".toByteArray())     // type
            put("isom".toByteArray())     // major_brand
            putInt(0x200)                 // minor_version
            put("isom".toByteArray())     // compatible_brand
            put("iso2".toByteArray())     // compatible_brand
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