package org.ytmuxer.mpfour

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.text.NumberFormat

class AddPosterMp3(val file: File,val imageFile: File,inputFile: File){
    val output= RandomAccessFile(file,"rw")
    val input= RandomAccessFile(inputFile,"r")
    val mp4Utils= utils()
    lateinit var source: DashedParser
    val movieTimeScale = 1000

    fun addPosterToImage(){
        source= DashedParser(input,false,0,input.length())
        source.parse()
        buildNonFMp4()
    }

    private fun buildNonFMp4() {
        val ftyp = mp4Utils.writeFtyp()
        output.seek(0)
        output.write(ftyp)
        output.close()
    }

}