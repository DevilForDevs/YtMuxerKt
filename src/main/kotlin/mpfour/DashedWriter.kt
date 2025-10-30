package mpfour

import java.io.File
import java.io.RandomAccessFile

class DashedWriter(file: File, val sources: List<DashedParser>, val progress: (Samples: String, percent: Int) -> Unit = { _, _ -> } ){


    val output= RandomAccessFile(file,"rw")
    val mp4Utils= utils()
    val movieTimeScale = 1000

    fun build(){
        val ftyp = mp4Utils.writeFtyp()
        output.seek(0)
        output.write(ftyp)
        writeMdat()

    }
    fun writeMdat() {
        val mdatStart = output.filePointer

        // Write placeholder for size + "mdat"
        output.writeInt(0)
        output.write("mdat".toByteArray())

        val videoSamples=sources[0].getSamples(true)
        val audioSamples=sources[1].getSamples(true)




    }



}


