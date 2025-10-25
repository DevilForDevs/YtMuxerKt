package org.ytmuxer.webm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer



class WebmMuxer(outputFile: File, private val sources: List<WebMParser>,val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ) {
     val output = RandomAccessFile(outputFile, "rw")
    var totalBlocksFromAllSources=0

    init {
        writeEbmlHeader()
        for (source in sources){
            totalBlocksFromAllSources+=source.totalBlocks
        }
    }

    private fun writeEbmlHeader() {
        val ebmlHeader = buildEbmlHeader()
        output.write(ebmlHeader)
    }

    private fun writeClusters(segmentStart: Long): MutableList<CueEntry> {
        val listOfCues = mutableListOf<CueEntry>()
        var clusterStart = 0L
        var clusterSizePos = 0L
        var clusterTimecode = 0L
        var lastCueTime = -1L
        var blocksProcessed = 0  // ✅ Track progress

        var videoBlock = sources[0].getBlock()
        var audioBlock = sources[1].getBlock()

        fun patchClusterSize() {
            if (clusterStart != 0L) {
                val clusterEnd = output.filePointer
                val clusterSize = clusterEnd - clusterStart
                output.seek(clusterSizePos)
                output.write(encodeVInt8(clusterSize, 8))
                output.seek(clusterEnd)
            }
        }

        while (videoBlock != null || audioBlock != null) {

            // Start new cluster on video keyframe
            if (videoBlock != null && videoBlock.isKeyframe) {
                patchClusterSize()

                val clusterHeaderStart = output.filePointer
                output.write(idToBytes(0x1F43B675))     // Cluster ID
                clusterSizePos = output.filePointer
                output.write(ByteArray(8))              // reserve size
                clusterStart = output.filePointer

                clusterTimecode = videoBlock.absoluteTimecode
                output.write(writeElement(0xE7, encodeTimecode(clusterTimecode.toInt())))

                // Add cue if new timecode
                if (clusterTimecode != lastCueTime) {
                    listOfCues.add(
                        CueEntry(
                            cueTime = clusterTimecode,
                            cueClusterPosition = clusterHeaderStart - segmentStart,
                            cueTrack = 1
                        )
                    )
                    lastCueTime = clusterTimecode
                }

                // Write blocks in cluster
                while (true) {
                    val nextBlock = when {
                        videoBlock != null && audioBlock != null ->
                            if (videoBlock.absoluteTimecode <= audioBlock.absoluteTimecode) videoBlock else audioBlock
                        videoBlock != null -> videoBlock
                        audioBlock != null -> audioBlock
                        else -> null
                    } ?: break

                    val relTime = nextBlock.absoluteTimecode - clusterTimecode
                    val track = if (nextBlock == videoBlock) 1 else 2
                    writeSimpleBlock(nextBlock, track, relTime)

                    // ✅ Increment processed blocks and report progress
                    blocksProcessed++
                    progress(
                        "Merging - $blocksProcessed/$totalBlocksFromAllSources Samples",
                        (blocksProcessed * 100 / totalBlocksFromAllSources).toInt()
                    )

                    if (nextBlock == videoBlock) videoBlock = sources[0].getBlock()
                    else audioBlock = sources[1].getBlock()

                    // Break on next video keyframe
                    if (videoBlock != null && videoBlock.isKeyframe && nextBlock != videoBlock) break
                }

            } else {
                // Write blocks if not starting a new cluster
                val nextBlock = when {
                    videoBlock != null && audioBlock != null ->
                        if (videoBlock.absoluteTimecode <= audioBlock.absoluteTimecode) videoBlock else audioBlock
                    videoBlock != null -> videoBlock
                    audioBlock != null -> audioBlock
                    else -> null
                } ?: break

                val relTime = nextBlock.absoluteTimecode - clusterTimecode
                val track = if (nextBlock == videoBlock) 1 else 2
                writeSimpleBlock(nextBlock, track, relTime)

                // ✅ Increment processed blocks and report progress
                blocksProcessed++
                progress(
                    "Merging - $blocksProcessed/$totalBlocksFromAllSources Samples",
                    (blocksProcessed * 100 / totalBlocksFromAllSources).toInt()
                )

                if (nextBlock == videoBlock) videoBlock = sources[0].getBlock()
                else audioBlock = sources[1].getBlock()
            }
        }

        patchClusterSize()
        return listOfCues
    }




    fun writeSegment() {
        // Segment header
        output.write(hexToBytes("18 53 80 67"))
        val segmentSizePos = output.filePointer
        output.write(ByteArray(8))
        val segmentStart = output.filePointer

        // Info & Tracks
        val infoPos = output.filePointer - segmentStart
        writeInfo(sources.maxOfOrNull { it.duration } ?: 0.0)

        val tracksPos = output.filePointer - segmentStart
        writeTracks()

        // Clusters
        val cueEntries = writeClusters(segmentStart)

        // Cues
        val cuesPos = output.filePointer - segmentStart
        writeCues(cueEntries)

        // SeekHead (at end)
        writeSeekHead(segmentStart, infoPos, tracksPos, cuesPos)

        // Patch Segment size
        val segmentEnd = output.filePointer
        val segmentSize = segmentEnd - segmentStart
        output.seek(segmentSizePos)
        output.write(encodeVInt8(segmentSize, 8))
        output.seek(segmentEnd)
    }




    private fun writeCues(cueEntries: List<CueEntry>) {
        output.write(idToBytes(0x1C53BB6B)) // Cues ID
        val cuesSizePos = output.filePointer
        output.write(ByteArray(8)) // Reserve size
        val cuesStart = output.filePointer

        val timecodeScale = 1000000 / 1000000.0 // TimecodeScale in milliseconds (1ms)

        for (cue in cueEntries) {
            val cuePoint = ByteArrayOutputStream()
            // Scale cueTime to timecode units (assuming cue.cueTime is in milliseconds)
            val scaledCueTime = (cue.cueTime / timecodeScale).toInt()
            cuePoint.write(writeElement(0xB3, encodeTimecode(scaledCueTime)))

            val cueTrackPositions = ByteArrayOutputStream()
            cueTrackPositions.write(writeElement(0xF7, encodeUInt(cue.cueTrack)))
            cueTrackPositions.write(writeElement(0xF1, encodeUInt(cue.cueClusterPosition))) // Already relative

            cuePoint.write(writeElement(0xB7, cueTrackPositions.toByteArray()))
            output.write(writeElement(0xBB, cuePoint.toByteArray()))
        }

        // Patch size
        val cuesEnd = output.filePointer
        val cuesSize = cuesEnd - cuesStart
        output.seek(cuesSizePos)
        output.write(encodeVInt8(cuesSize, 8))
        output.seek(cuesEnd)
    }

    private fun encodeUInt(value: Long): ByteArray {
        // Find minimal byte count
        var v = value
        var size = 1
        while (v > 0xFF) {
            size++
            v = v shr 8
        }

        // Write big-endian bytes
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[size - 1 - i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        return result
    }
    private fun writeSeekHead(segmentStart: Long, infoPos: Long, tracksPos: Long, cuesPos: Long) {
        output.write(idToBytes(0x114D9B74)) // SeekHead ID
        val seekHeadSizePos = output.filePointer
        output.write(ByteArray(8)) // reserve size
        val seekHeadStart = output.filePointer

        val seekEntries = listOf(
            0x1549A966L to infoPos,
            0x1654AE6BL to tracksPos,
            0x1C53BB6BL to cuesPos
        )

        for ((id, pos) in seekEntries) {
            val seek = ByteArrayOutputStream()
            seek.write(writeElement(0x53AB, idToBytes(id)))
            seek.write(writeElement(0x53AC, encodeUInt(pos))) // relative to segment start
            output.write(writeElement(0x4DBB, seek.toByteArray()))
        }

        val seekHeadEnd = output.filePointer
        val seekHeadSize = seekHeadEnd - seekHeadStart
        output.seek(seekHeadSizePos)
        output.write(encodeVInt8(seekHeadSize, 8))
        output.seek(seekHeadEnd)
    }


    fun encodeTimecode(timecode: Int): ByteArray {
        // Convert int to minimum bytes needed
        val bytes = mutableListOf<Byte>()
        var value = timecode
        var shift = 24

        // Determine number of bytes needed (non-zero MSB)
        var started = false
        for (i in 3 downTo 0) {
            val b = ((value shr (i * 8)) and 0xFF).toByte()
            if (b.toInt() != 0 || started) {
                bytes.add(b)
                started = true
            }
        }

        // If timecode is 0, at least 1 byte is required
        if (bytes.isEmpty()) bytes.add(0x00)

        return bytes.toByteArray()
    }

    fun writeSimpleBlock(block: BlockEntry, track: Int, blockTimeCode: Long) {
        // --- Read raw VP9 frame ---
        val rawFrame = ByteArray(block.frameSize.toInt())
        block.rf.seek(block.frameAbsoluteOffset)
        block.rf.readFully(rawFrame)

        // --- Track number as VInt ---
        val trackVInt = encodeVInt(track.toLong())

        // --- Relative timecode ---
        val relativeTimecode = (blockTimeCode).toShort()
        val timecode = ByteBuffer.allocate(2).putShort(relativeTimecode).array()

        // --- Flags ---
        val flags = if (block.isKeyframe) 0x80.toByte() else 0x00.toByte()

        // --- Build SimpleBlock data ---
        val blockData = trackVInt + timecode + byteArrayOf(flags) + rawFrame

        // --- Write SimpleBlock element ---
        output.write(writeMasterElementBytes(0xA3, blockData))
    }





    fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            ((value.toInt() shr 8) and 0xFF).toByte(),
            (value.toInt() and 0xFF).toByte()
        )
    }

    fun encodeVInt8(value: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        for (i in 0 until length) {
            buffer[length - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        buffer[0] = (buffer[0].toInt() or (1 shl (8 - length))).toByte() // Set VINT marker bit
        return buffer
    }
    private fun writeInfo(duration: Double) {
        val info = ByteArrayOutputStream()
        info.write(ebmlUInt(0x2AD7B1,  1000000)) // TimecodeScale = 1ms
        info.write(ebmlFloat(0x4489, duration)) // Duration = 10.0s (example)
        info.write(ebmlString(0x4D80, "ChatGPTMuxer"))
        info.write(ebmlString(0x5741, "KotlinMuxer"))

        writeMasterElement(0x1549A966, info.toByteArray()) // Info
    }

    private fun writeTracks() {
        val tracks = ByteArrayOutputStream()
        var trackCounter = 1L // sequential track number

        sources.forEach { src ->
            src.tracks.forEach { trackInfo ->
                val entry = ByteArrayOutputStream()

                // --- TrackNumber depends on type ---
                entry.write(ebmlUInt(0xD7, trackCounter))
                trackCounter++ // increment for next track

                // TrackUID
                entry.write(ebmlUInt(0x73C5, (1000 + trackCounter)))

                // TrackType: 1 = video, 2 = audio
                entry.write(ebmlUInt(0x83, trackInfo.type))

                // Codec info
                entry.write(ebmlString(0x86, trackInfo.codecID))
                if (trackInfo.codecName.isNotEmpty()) {
                    entry.write(ebmlString(0x258688, trackInfo.codecName))
                }

                // Language
                entry.write(ebmlString(0x22B59C, trackInfo.language))

                // CodecPrivate
                if (trackInfo.codecPrivate.isNotEmpty()) {
                    entry.write(writeElement(0x63A2, trackInfo.codecPrivate))
                }

                // --- Video fields only for video tracks ---
                if (trackInfo.type == 1L) {
                    val video = ByteArrayOutputStream()
                    if (trackInfo.width > 0) video.write(ebmlUInt(0xB0, trackInfo.width))
                    if (trackInfo.height > 0) video.write(ebmlUInt(0xBA, trackInfo.height))
                    entry.write(writeMasterElementBytes(0xE0, video.toByteArray()))
                }

                // --- Audio fields only for audio tracks ---
                if (trackInfo.type == 2L) {
                    val audio = ByteArrayOutputStream()
                    if (trackInfo.samplingFrequency > 0) audio.write(ebmlFloat(0xB5, trackInfo.samplingFrequency))
                    if (trackInfo.channels > 0) audio.write(ebmlUInt(0x9F, trackInfo.channels))
                    entry.write(writeMasterElementBytes(0xE1, audio.toByteArray()))
                }

                // --- Write TrackEntry ---
                tracks.write(writeMasterElementBytes(0xAE, entry.toByteArray()))
            }
        }

        // --- Write top-level Tracks element ---
        writeMasterElement(0x1654AE6B, tracks.toByteArray())
    }




    private fun ebmlUInt(id: Long, value: Long): ByteArray {
        val data = ByteBuffer.allocate(8).putLong(value).array().dropWhile { it == 0.toByte() }.toByteArray()
        return writeElement(id, data)
    }

    private fun ebmlFloat(id: Long, value: Double): ByteArray {
        val buf = ByteBuffer.allocate(8).putDouble(value).array()
        return writeElement(id, buf)
    }

    private fun ebmlString(id: Long, value: String): ByteArray {
        return writeElement(id, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeElement(id: Long, data: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        val sizeBytes = encodeVInt(data.size.toLong())
        return idBytes + sizeBytes + data
    }

    private fun writeMasterElement(id: Long, data: ByteArray) {
        output.write(writeMasterElementBytes(id, data))
    }

    private fun writeMasterElementBytes(id: Long, data: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        val sizeBytes = encodeVInt(data.size.toLong())
        return idBytes + sizeBytes + data
    }

    private fun idToBytes(id: Long): ByteArray {
        val bytes = ByteBuffer.allocate(8).putLong(id).array()
        return bytes.dropWhile { it == 0.toByte() }.toByteArray()
    }
    private fun hexToBytes(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}


