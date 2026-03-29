package muxer.webm

import java.io.File
import java.io.RandomAccessFile


class WebmMuxer(outputFile: File, private val sources: List<WebMParser>, val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ) {
     val output = RandomAccessFile(outputFile, "rw")
     var totalBlocksFromAllSources=0
     val helper= Helper()


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


    private fun writeClusters() {

        var clusterStart = 0L
        var clusterSizePos = 0L
        var clusterTimecode = 0L

        var videoBlock = sources[0].getBlock()
        var audioBlock = sources[1].getBlock()

        fun patchClusterSize() {
            if (clusterStart != 0L) {
                val clusterEnd = output.filePointer
                val clusterSize = clusterEnd - clusterStart
                output.seek(clusterSizePos)
                output.write(helper.encodeVInt8(clusterSize,8))
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

                if (nextBlock == videoBlock) videoBlock = sources[0].getBlock()
                else audioBlock = sources[1].getBlock()
            }
        }

        patchClusterSize()

    }

    fun writeSimpleBlock(block: BlockEntry, track: Int, blockTimeCode: Long) {
        val blockData=helper.writeSimpleBlock(block,track,blockTimeCode)
        output.write(helper.writeMasterElementBytes(0xA3, blockData))
    }

    fun writeSegment() {
        // Segment ID
        output.write(helper.hexToBytes("18 53 80 67"))

        val segmentSizePos = output.filePointer
        output.write(ByteArray(8)) // reserve size

        val segmentStart = output.filePointer





        val segmentEnd = output.filePointer
        val segmentSize = segmentEnd - segmentStart

        output.seek(segmentSizePos)
        output.write(helper.encodeVInt8(segmentSize,8))
        output.seek(segmentEnd)
    }


    private fun writeMasterElement(id: Long, data: ByteArray) {
        output.write(helper.writeMasterElementBytes(id, data))
    }

    private fun writeInfo(duration: Double) {
        val info=helper.muxerInfoBytes(duration)
        writeMasterElement(0x1549A966, info)
    }

    private fun writeTracks() {
        val tracks=helper.trakBytes(sources)
        writeMasterElement(0x1654AE6B, tracks.toByteArray())
    }




}


