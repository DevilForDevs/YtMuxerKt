package muxer.webm

import java.io.File
import java.io.RandomAccessFile


class WebmMuxer(outputFile: File, private val sources: List<WebMParser>, val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ) {
     val output = RandomAccessFile(outputFile, "rw")
     var totalBlocksFromAllSources=0
     val helper= Helper()


    val listBuffer = mutableListOf<ByteArray>()
    var firstCluter: Long?=null


    init {
        // this code must be inside init or a function
        listBuffer.add(byteArrayOf(
            0x11, 0x4d, 0x9b.toByte(), 0x74, 0xbe.toByte(),
            0x4d, 0xbb.toByte(), 0x8b.toByte(),
            0x53, 0xab.toByte(), 0x84.toByte(), 0x15, 0x49, 0xa9.toByte(), 0x66, 0x53,
            0xac.toByte(), 0x81.toByte(),
            /* info offset */ 0x4F.toByte(),
            0x4d, 0xbb.toByte(), 0x8b.toByte(), 0x53, 0xab.toByte(),
            0x84.toByte(), 0x16, 0x54, 0xae.toByte(), 0x6b, 0x53, 0xac.toByte(), 0x81.toByte(),
            /* tracks offset */ 0x83.toByte(),
            0x4d, 0xbb.toByte(), 0x8e.toByte(), 0x53, 0xab.toByte(), 0x84.toByte(), 0x1f.toByte(),
            0x43, 0xb6.toByte(), 0x75, 0x53, 0xac.toByte(), 0x84.toByte(),
            /* cluster offset [2] */ 0x00, 0x00, 0x00, 0x00,
            0x4d, 0xbb.toByte(), 0x8e.toByte(), 0x53, 0xab.toByte(), 0x84.toByte(), 0x1c.toByte(), 0x53,
            0xbb.toByte(), 0x6b, 0x53, 0xac.toByte(), 0x84.toByte(),
            /* cues offset [7] */ 0x00, 0x00, 0x00, 0x00
        ))


    }


    init {
        writeEbmlHeader()
        for (source in sources){
            totalBlocksFromAllSources+=source.totalBlocks
        }
    }

    fun overwriteUInt32At(
        raf: RandomAccessFile,
        position: Long,
        value: Int
    ) {
        val current = raf.filePointer

        raf.seek(position)

        raf.write((value shr 24) and 0xFF)
        raf.write((value shr 16) and 0xFF)
        raf.write((value shr 8) and 0xFF)
        raf.write(value and 0xFF)

        raf.seek(current) // restore pointer
    }

    private fun writeEbmlHeader() {
        val ebmlHeader = buildEbmlHeader()
        output.write(ebmlHeader)
    }



    fun writeSegment() {
        // --- Start of the Segment ---
        output.write(helper.hexToBytes("18 53 80 67")) // Segment ID

        val segmentSizePos = output.filePointer
        output.write(ByteArray(8)) // placeholder for segment size
        val segmentStart = output.filePointer

        val combined = listBuffer.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        output.write(combined)

        writeInfo(sources.maxOfOrNull { it.duration } ?: 0.0)
        writeTracks()
        writeClusters(segmentStart)

        if (firstCluter!=null){
            val clusterStartoffsetRelativeTosegment=firstCluter!!-segmentStart
            overwriteUInt32At(
                output,
                segmentStart+46,
                clusterStartoffsetRelativeTosegment.toInt()
            )
        }



        // --- Finish Segment size ---
        val segmentEnd = output.filePointer
        val segmentSize = segmentEnd - segmentStart
        output.seek(segmentSizePos)
        output.write(helper.encodeVInt8(segmentSize, 8))
        output.seek(segmentEnd)
    }

    fun printHexFromFile(raf: RandomAccessFile, position: Long, length: Int) {
        val current = raf.filePointer

        val buffer = ByteArray(length)
        raf.seek(position)
        raf.readFully(buffer)

        buffer.forEachIndexed { i, b ->
            println(String.format("%03d : %02X", i, b))
        }

        raf.seek(current) // restore pointer
    }





    private fun writeInfo(duration: Double) {
        val info=helper.muxerInfoBytes(duration)
        val idBytes = helper.idToBytes(0x1549A966)
        val sizeBytes = encodeVInt(info.size.toLong())
        val wholeElemnet=idBytes + sizeBytes + info
        output.write(wholeElemnet)
    }

    private fun writeTracks() {
        val tracks = helper.trakBytes(sources).toByteArray()
        val idBytes = helper.idToBytes(0x1654AE6B)
        val sizeBytes = encodeVInt(tracks.size.toLong())
        val wholeElemnet = idBytes + sizeBytes + tracks
        output.write(wholeElemnet)
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
                output.write(helper.encodeVInt8(clusterSize, 8))
                output.seek(clusterEnd)
            }
        }

        while (videoBlock != null || audioBlock != null) {

            // Start new cluster on video keyframe
            if (videoBlock != null && videoBlock.isKeyframe) {
                patchClusterSize()

                val clusterHeaderStart = output.filePointer
                output.write(helper.idToBytes(0x1F43B675))     // Cluster ID
                clusterSizePos = output.filePointer
                output.write(ByteArray(8))              // reserve size
                clusterStart = output.filePointer

                clusterTimecode = videoBlock.absoluteTimecode
                output.write(helper.writeElement(0xE7, helper.encodeTimecode(clusterTimecode.toInt())))

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

                    // ✅ Increment processed blocks
                    blocksProcessed++

                    // ✅ Filtered progress updates
                    if (blocksProcessed % 2000 == 0 || nextBlock == videoBlock && videoBlock == null && audioBlock == null) {
                        progress(
                            "Merging - $blocksProcessed/$totalBlocksFromAllSources Samples",
                            (blocksProcessed * 100 / totalBlocksFromAllSources).toInt()
                        )
                    }

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

                // ✅ Increment processed blocks
                blocksProcessed++

                // ✅ Filtered progress updates
                if (blocksProcessed % 2000 == 0 || nextBlock == videoBlock && videoBlock == null && audioBlock == null) {
                    progress(
                        "Merging - $blocksProcessed/$totalBlocksFromAllSources Samples",
                        (blocksProcessed * 100 / totalBlocksFromAllSources).toInt()
                    )
                }

                if (nextBlock == videoBlock) videoBlock = sources[0].getBlock()
                else audioBlock = sources[1].getBlock()
            }
        }

        patchClusterSize()
        return listOfCues
    }

    fun writeSimpleBlock(block: BlockEntry, track: Int, blockTimeCode: Long) {
        val blockData=helper.writeSimpleBlock(block,track,blockTimeCode)
        output.write(helper.writeMasterElementBytes(0xA3, blockData))
    }

}


