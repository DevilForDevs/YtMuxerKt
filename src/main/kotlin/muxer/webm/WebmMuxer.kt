package muxer.webm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile


class WebmMuxer(outputFile: File, private val sources: List<WebMParser>, val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ) {
     val output = RandomAccessFile(outputFile, "rw")
     var totalBlocksFromAllSources=0
     val helper= Helper()

    init {
        /*writeEbmlHeader()*/
        for (source in sources){
            totalBlocksFromAllSources+=source.totalBlocks
        }
    }

    private fun writeEbmlHeader() {
        val ebmlHeader = buildEbmlHeader()
        output.write(ebmlHeader)
    }

    fun writeSegment() {
        val writtenSegmentInfo=helper.writeSegmentHeader(output)

        //learning how create void and other elements in empty bytes
        val dummySize=300
        val startOfDummyBytes=output.filePointer
        output.write(ByteArray(dummySize))

        helper.patchSegment(output,writtenSegmentInfo)

        output.seek(startOfDummyBytes)
        val seekInfo=helper.initiateSeekhead(output)
        helper.patchSeekHead(output,seekInfo)

        val remaining = dummySize - (output.filePointer - startOfDummyBytes).toInt()
        val voidInfo = helper.initiateVoidElement(output, remaining)
        helper.patchVoidElement(output,voidInfo)


        helper.printHexFromFile(output,0,output.length().toInt())

    }





}


