package muxer.webm

import okhttp3.internal.addHeaderLenient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile


class WebmMuxer(outputFile: File, private val sources: List<WebMParser>, val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ) {
     val output = RandomAccessFile(outputFile, "rw")
     var totalBlocksFromAllSources=0
     val helper= Helper()

    val listBuffer = mutableListOf<ByteArray>()

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


        // --- Finish Segment size ---
        val segmentEnd = output.filePointer
        val segmentSize = segmentEnd - segmentStart
        output.seek(segmentSizePos)
        output.write(helper.encodeVInt8(segmentSize, 8))
        output.seek(segmentEnd)
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
}


