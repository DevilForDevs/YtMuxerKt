package mpfour
import org.ytmuxer.webm.convertBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder


class DashedParser(file: File,val doLogging: Boolean){
    val reader= RandomAccessFile(file,"r")
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


    var firstMoofPayloadOffset=0L
    var firstMoofPayloadSize=0L
    var entriesToSkip_Offset=0L


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
        reader.seek(0)

        // --- Check ISO base media header (ftyp or styp) ---
        if (reader.length() < 8) {
            throw IllegalStateException("Invalid container: file too small to be a valid ISO file.")
        }

        val startOffset = reader.filePointer
        val size = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val boxType = String(typeBytes, Charsets.US_ASCII)

        if (boxType != "ftyp" && boxType != "styp") {
            throw IllegalStateException("Invalid container: expected 'ftyp' or 'styp' at offset 0, found '$boxType'")
        }

        if (doLogging) {
            println("Valid ISO header found: '$boxType' at offset $startOffset (size=$size)")
        }

        // --- Move back to start to re-read all boxes ---
        reader.seek(0)

        // --- Main parsing loop ---
        while (reader.filePointer + 8 <= reader.length()) {
            val startOffsetLoop = reader.filePointer

            val sizeLoop = reader.readInt().toLong()
            if (sizeLoop < 8) {
                throw IllegalStateException("Invalid box size $sizeLoop at offset $startOffsetLoop")
            }

            val typeBytesLoop = ByteArray(4).also { reader.readFully(it) }
            val boxTypeLoop = String(typeBytesLoop, Charsets.US_ASCII)

            val payloadOffset = reader.filePointer
            val payloadSize = sizeLoop - 8

            if (doLogging) {
                println(
                    "Box: $boxTypeLoop | BoxOffset: $startOffsetLoop | BoxSize: $sizeLoop | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (boxTypeLoop) {
                "moov" -> {
                    val payload = ByteArray(payloadSize.toInt())
                    reader.readFully(payload)
                    parseMoov(payload)
                }

                "moof" -> {
                    if (firstMoofPayloadOffset==0L){
                        firstMoofPayloadOffset=payloadOffset
                        firstMoofPayloadSize=payloadSize
                    }
                    val payload = ByteArray(payloadSize.toInt())
                    reader.readFully(payload)
                    countSamplesInMoof(payload)
                }

                else -> {
                    // Skip unknown or irrelevant boxes
                    reader.seek(payloadOffset + payloadSize)
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



    fun parseMoov(payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.remaining() >= 8) {
            val boxStart = buffer.position()
            val boxSize = buffer.int
            val boxType = buffer.int

            when (boxType) {
                0x6D766864 -> parseMvhd(buffer.slice().limit(boxSize - 8) as ByteBuffer) // "mvhd"
                0x7472616B -> parseTrak(buffer.slice().limit(boxSize - 8) as ByteBuffer) // "trak"
            }
            buffer.position(boxStart + boxSize)
        }
    }

    fun parseMvhd(buffer: ByteBuffer) {

        val version = buffer.get().toInt()
        buffer.get() // flags
        buffer.get()
        buffer.get()
        if (version == 1) {
            buffer.long // creation_time
            buffer.long // modification_time
            movieTimescale = buffer.int
            movieDuration = buffer.long

        } else {
            buffer.int
            buffer.int
            movieTimescale = buffer.int
            movieDuration = buffer.int.toLong()
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
    fun getSamples(initialChunk: Boolean) {
        val mdatPayloadOffset = firstMoofPayloadOffset + firstMoofPayloadSize + 8

        val trafBoxes = parseTraf(reader, firstMoofPayloadOffset, firstMoofPayloadSize)
        if (trafBoxes.isEmpty()) {
            println("No traf boxes found")
            return
        }

        val trun = trafBoxes[0].truns.firstOrNull()
        if (trun == null) {
            println("No trun found")
            return
        }

        println("Sample count = ${trun.sampleCount}")

        // Seek to trun.entriesOffset to read sample table
        reader.seek(trun.entriesOffset)

        val hasDuration = trun.flags and 0x000100 != 0
        val hasSize = trun.flags and 0x000200 != 0
        val hasFlags = trun.flags and 0x000400 != 0
        val hasCTO = trun.flags and 0x000800 != 0

        // Read the first sample entry
        var sampleSize = 0
        if (hasDuration) reader.readInt() // skip duration
        if (hasSize) sampleSize = reader.readInt()
        if (hasFlags) reader.readInt() // skip flags
        if (hasCTO) reader.readInt() // skip CTO

        println("First sample size = $sampleSize bytes")

        // Read full frame from mdat
        reader.seek(mdatPayloadOffset)
        val sampleData = ByteArray(sampleSize)
        reader.readFully(sampleData)

        println("Read first frame (${sampleData.size} bytes)")

        // Save .h264 file (auto uses stsdBox)
        val h264File = saveH264Frame(
            sampleData,
            outputDir = File("frames"),
            fileName = "frame.h264",
            stsdBox = stsdBox!!
        )
        println("Saved H.264 frame: ${h264File.absolutePath}")

        // Optional: call FFmpeg (if available on system path)
        try {
            val outputImage = File("keyframe.jpg")
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", h264File.absolutePath,
                "-frames:v", "1",
                outputImage.absolutePath
            ).inheritIO().start()
            process.waitFor()

            if (outputImage.exists()) {
                println("✅ Extracted keyframe image: ${outputImage.absolutePath}")
            } else {
                println("⚠️ FFmpeg did not produce an image. Check SPS/PPS or FFmpeg output.")
            }
        } catch (e: Exception) {
            println("⚠️ FFmpeg not found or failed to execute: ${e.message}")
        }
    }

    /*fun saveH264Frame(
        frame: ByteArray,
        outputDir: File = File(System.getProperty("java.io.tmpdir")),
        fileName: String = "frame.h264",
        sps: ByteArray? = null,
        pps: ByteArray? = null
    ): File {
        val (resolvedSps, resolvedPps) = when {
            sps != null && pps != null -> Pair(sps, pps)
            stsdBox != null -> extractSpsPpsFromStsd(stsdBox!!) ?: error("Failed to parse SPS/PPS from stsdBox")
            else -> error("No SPS/PPS data available")
        }

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
    }*/




















}

/*moof
 └── mfhd (Movie Fragment Header)
 └── traf (Track Fragment)
      ├── tfhd (Track Fragment Header)
      ├── tfdt (Track Fragment Decode Time)
      ├── trun (Track Run)  ← contains the actual sample info
      └── (optional: sdtp, senc, subs, etc.)
*/