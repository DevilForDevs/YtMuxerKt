package org.ytmuxer.mpfour
import org.ytmuxer.webm.convertBytes
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DashedParser(val reader: RandomAccessFile,private val doLogging: Boolean,private val baseOffset: Long,private val baseEndOffset: Long){

    var currentBox = Box()
    var movieTimescale: Int = -1
    var movieDuration: Long = -1
    val ATOM_TKHD: Int = 0x746B6864
    var ATOM_MDIA: Int = 0x6D646961

    var mediaTimescale: Int = -1
    var mediaDuration: Long = -1
    var language: String = ""

    var trackDuration=-1L

    // From hdlr
    var handlerType: String = ""

    var stsdBox: ByteArray? =null

    val moofs = mutableListOf<Box>()
    var currentMoofIndex=0;
    var lastRetrivedSampleIndex=-1
    val entries = mutableListOf<TrunSampleEntry>()

    var totalSamplesFromMoof=0


    //fields updated from dashedWriter
    val keySamplesIndices=mutableListOf<Int>()
    val chunksOffsets=mutableListOf<Long>()
    val samplesSizes=mutableListOf<Int>()
    val cttsEntries = mutableListOf<Pair<Int, Int>>()

    var lastOffset: Int? = null
    var runLength = 0
    var initialChunk=true
    val samplesPerChunkList = mutableListOf<Int>()




    fun parse() {
        reader.seek(baseOffset)

        while (reader.filePointer + 8 <= baseEndOffset) {
            val startOffset = reader.filePointer

            val size = reader.readInt().toLong()
            if (size < 8) throw IllegalStateException("Invalid box size $size at offset $startOffset")

            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val boxType = String(typeBytes, Charsets.US_ASCII)

            if (doLogging) {
                println("Box $boxType At: $startOffset  Size: ${convertBytes(size)}")
            }

            currentBox = Box(type = boxType, offset = startOffset, size = size)

            when (boxType) {
                "moov" -> {
                    val payload = ByteArray((size - 8).toInt())
                    reader.readFully(payload)
                    parseMoov(payload, doLogging)
                }
                "moof" -> {
                    moofs.add(Box(type = boxType, offset = startOffset, size = size))
                    val payload = ByteArray((size - 8).toInt())
                    reader.readFully(payload)
                    countSamplesInMoof(payload)
                }
                else -> {
                    // skip other boxes
                    reader.seek(startOffset + size)
                }
            }
        }
    }


    fun countSamplesInMoof(moofData: ByteArray): Int {
        val buffer = ByteBuffer.wrap(moofData).order(ByteOrder.BIG_ENDIAN)
        var sampleCount = 0

        while (buffer.remaining() >= 8) {
            val startPos = buffer.position()
            if (buffer.remaining() < 8) break

            val size = buffer.int
            val typeBytes = ByteArray(4)
            buffer.get(typeBytes)
            val type = String(typeBytes)

            if (type == "traf") {
                val trafEnd = startPos + size
                while (buffer.position() < trafEnd) {
                    val boxStart = buffer.position()
                    if (buffer.remaining() < 8) break

                    val subSize = buffer.int
                    val subTypeBytes = ByteArray(4)
                    buffer.get(subTypeBytes)
                    val subType = String(subTypeBytes)

                    if (subType == "trun") {
                        if (subSize >= 12) {
                            buffer.position(buffer.position() + 4) // skip version & flags
                            val count = buffer.int
                            sampleCount += count
                            buffer.position(boxStart + subSize) // skip remaining of trun
                        } else {
                            break // malformed trun
                        }
                    } else {
                        buffer.position(boxStart + subSize)
                    }
                }
            } else {
                buffer.position(startPos + size)
            }
        }
        totalSamplesFromMoof+=sampleCount
        return sampleCount
    }



    fun parseMoov(payload: ByteArray,doLogging: Boolean) {
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.remaining() >= 8) {
            val boxStart = buffer.position()
            val boxSize = buffer.int
            val boxType = buffer.int

            when (boxType) {
                0x6D766864 -> {
                    if (doLogging){
                        println("Box $boxType At: $boxStart  Size: ${convertBytes(boxSize.toLong())}")
                    }
                    parseMvhd(buffer.slice().limit(boxSize - 8) as ByteBuffer,doLogging)
                } // "mvhd"
                0x7472616B -> {
                    if (doLogging){
                        println("Box $boxType At: $boxStart  Size: ${convertBytes(boxSize.toLong())}")
                    }
                    parseTrak(buffer.slice().limit(boxSize - 8) as ByteBuffer)
                } // "trak"
            }
            buffer.position(boxStart + boxSize)
        }
    }

    fun parseMvhd(buffer: ByteBuffer,doLogging: Boolean) {

        val version = buffer.get().toInt()
        buffer.get() // flags
        buffer.get()
        buffer.get()
        if (version == 1) {
            buffer.long // creation_time
            buffer.long // modification_time
            movieTimescale = buffer.int
            movieDuration = buffer.long
            if (doLogging){
                println("Box: Mvhd MovieTimescale: $movieTimescale  Movie Duration: $movieDuration")
            }


        } else {
            buffer.int
            buffer.int
            movieTimescale = buffer.int
            movieDuration = buffer.int.toLong()
            if (doLogging){
                println("Box: Mvhd MovieTimescale: $movieTimescale  Movie Duration: $movieDuration")
            }
        }


        buffer.int // rate
        buffer.short // volume
        buffer.position(buffer.position() + 10 + 36 + 24) // skip reserved/matrix
    }
    fun parseTrak(data: ByteBuffer) {
        while (data.remaining() >= 8) {
            val boxStart = data.position()
            val boxSize = data.int
            val boxType = data.int

            when (boxType) {
                ATOM_MDIA -> parseMdia(data.slice().limit(boxSize - 8) as ByteBuffer) // "mdia"
                ATOM_TKHD->parseTkhd(data.slice().limit(boxSize - 8) as ByteBuffer)//tkhd
            }
            data.position(boxStart + boxSize)
        }
    }
    fun parseTkhd(data: ByteBuffer) {
        data.order(ByteOrder.BIG_ENDIAN)
        val version = data.get().toInt() and 0xFF
        val flags = (data.get().toInt() and 0xFF shl 16) or
                (data.get().toInt() and 0xFF shl 8) or
                (data.get().toInt() and 0xFF)

        val startPos = data.position()

        val trackId: Int
        if (version == 1) {
            if (data.remaining() < 32) return  // Avoid overflow
            data.position(data.position() + 16) // creation + modification (8+8)
            trackId = data.int
            data.int // reserved
            trackDuration = data.long
        } else {
            if (data.remaining() < 20) return
            data.position(data.position() + 8) // creation + modification (4+4)
            trackId = data.int
            data.int // reserved
            trackDuration = data.int.toLong() and 0xFFFFFFFF
        }

        // Safely skip rest if remaining bytes exist
        val skipBytes = 8 + 2 + 2 + 2 + 2 + 36 + 4 + 4
        if (data.remaining() >= skipBytes) {
            data.position(data.position() + skipBytes)
        } else {
            // Avoid buffer overflow
            data.position(data.limit())
        }

       /* println("🎯 trackId=$trackId, duration=$trackDuration")*/
    }

    fun parseMdia(data: ByteBuffer) {
        while (data.remaining() >= 8) {
            val boxStart = data.position()
            val boxSize = data.int
            val boxType = data.int

            when (boxType) {
                0x6D646864 -> parseMdhd(data.slice().limit(boxSize - 8) as ByteBuffer) // "mdhd"
                0x68646C72 -> parseHdlr(data.slice().limit(boxSize - 8) as ByteBuffer) // "hdlr"
                0x6D696E66 -> parseMinf(data.slice().limit(boxSize - 8) as ByteBuffer) // "minf"
            }
            data.position(boxStart + boxSize)
        }
    }
    fun parseMdhd(buffer: ByteBuffer) {
        val version = buffer.get().toInt()
        buffer.position(buffer.position() + 3) // skip flags

        if (version == 1) {
            buffer.long
            buffer.long
            mediaTimescale = buffer.int
            mediaDuration = buffer.long
        } else {
            buffer.int
            buffer.int
            mediaTimescale = buffer.int
            mediaDuration = buffer.int.toLong()
        }

        val lang = buffer.short
        language = decodeLanguage(lang)
    }
    fun decodeLanguage(code: Short): String {
        return listOf(
            ((code.toInt() shr 10) and 0x1F) + 0x60,
            ((code.toInt() shr 5) and 0x1F) + 0x60,
            (code.toInt() and 0x1F) + 0x60
        ).joinToString("") { it.toChar().toString() }
    }
    fun parseHdlr(buffer: ByteBuffer) {
        buffer.position(buffer.position() + 4) // version + flags
        buffer.int // pre_defined
        val handler = ByteArray(4)
        buffer.get(handler)
        handlerType = String(handler, Charsets.US_ASCII)
    }
    fun parseMinf(data: ByteBuffer) {
        while (data.remaining() >= 8) {
            val boxStart = data.position()
            val boxSize = data.int
            val boxType = data.int

            if (boxType == 0x7374626C) { // "stbl"
                parseStbl(data.slice().limit(boxSize - 8) as ByteBuffer)
            }
            data.position(boxStart + boxSize)
        }
    }
    fun parseStbl(data: ByteBuffer) {
        while (data.remaining() >= 8) {
            val boxStart = data.position()
            val boxSize = data.int
            val boxType = data.int

            val typeStr = String(
                ByteBuffer.allocate(4).putInt(boxType).array()
            )

            if (typeStr == "stsd") {
                // Rewind back to box start to capture full box (size + type + content)
                data.position(boxStart)
                stsdBox = ByteArray(boxSize)
                data.get(stsdBox)
                break
            }

            data.position(boxStart + boxSize)
        }
    }
    fun getSamples(initialChunk: Boolean): MutableList<TrunSampleEntry> {
        entries.clear()
        val targetSamples = if (initialChunk) 2 else 6

        while (currentMoofIndex < moofs.size) {
            val currentMoof = moofs[currentMoofIndex]
            val moofEnd = currentMoof.offset + currentMoof.size
            var moofPayloadStart = currentMoof.offset + 8

            while (moofPayloadStart + 8 < moofEnd) {
                reader.seek(moofPayloadStart)
                val boxHeader = ByteArray(8)
                reader.readFully(boxHeader)
                val innerBoxSize = ByteBuffer.wrap(boxHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
                val innerBoxType = String(boxHeader, 4, 4, Charsets.US_ASCII)

                if (innerBoxType == "traf") {
                    val trafStart = reader.filePointer
                    val trafEnd = trafStart + (innerBoxSize - 8)

                    while (reader.filePointer + 8 <= trafEnd) {
                        val innerHeader = ByteArray(8)
                        reader.readFully(innerHeader)

                        val innerSize = ByteBuffer.wrap(innerHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                        val innerType = String(innerHeader, 4, 4, Charsets.US_ASCII)

                        if (innerType == "trun") {
                            val versionAndFlags = ByteArray(4)
                            reader.readFully(versionAndFlags)
                            val version = versionAndFlags[0].toInt() and 0xFF
                            val flags = ((versionAndFlags[1].toInt() and 0xFF) shl 16) or
                                    ((versionAndFlags[2].toInt() and 0xFF) shl 8) or
                                    (versionAndFlags[3].toInt() and 0xFF)

                            val sampleCount = reader.readInt()

                            val dataOffset = if ((flags and 0x000001) != 0) reader.readInt() else 0

                            // Optional fields presence
                            val hasDuration = (flags and 0x000100) != 0
                            val hasSize = (flags and 0x000200) != 0
                            val hasFlags = (flags and 0x000400) != 0
                            val hasCto = (flags and 0x000800) != 0

                            // Fallback defaults if tfhd not parsed yet (replace with actual tfhd values if available)
                            val defaultDuration = 1024
                            val defaultSize = 0
                            val defaultFlags = 0

                            val startIndex = lastRetrivedSampleIndex + 1
                            val sampleOffsetStart = currentMoof.offset + dataOffset
                            var sampleOffset = sampleOffsetStart

                            for (i in 0 until sampleCount) {
                                val duration = if (hasDuration) reader.readInt() else defaultDuration
                                val size = if (hasSize) reader.readInt() else defaultSize
                                val flagsPerSample = if (hasFlags) reader.readInt() else defaultFlags
                                val cto = if (hasCto) reader.readInt() else 0

                                val isKeyframe = (flagsPerSample and 0x00010000) == 0

                                if (i < startIndex) {
                                    sampleOffset += size
                                    continue
                                }

                                entries.add(
                                    TrunSampleEntry(
                                        index = i,
                                        size = size,
                                        offset = sampleOffset,
                                        duration = duration,
                                        flags = flagsPerSample,
                                        compositionTimeOffset = cto,
                                        isSyncSample = isKeyframe
                                    )
                                )

                                sampleOffset += size
                                lastRetrivedSampleIndex = i

                                if (entries.size >= targetSamples) {
                                    return entries
                                }
                            }

                            // ✅ Finished reading this moof's samples
                            currentMoofIndex++
                            lastRetrivedSampleIndex = -1
                        }


                        reader.seek(reader.filePointer + (innerSize - 8))
                    }
                }

                moofPayloadStart += innerBoxSize
            }
        }

        return if (entries.size > targetSamples) {
            entries.subList(0, targetSamples)
        } else {
            entries
        }

    }


}