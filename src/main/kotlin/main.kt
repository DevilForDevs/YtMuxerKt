import mpfour.DashedParser
import mpfour.DashedWriter
import java.io.File
import java.io.RandomAccessFile

import java.io.*
import java.nio.file.Files

fun main() {
    val video = File("ULAhmUlf5D0(136).mp4")
    val parsedVideo = DashedParser(video, false)
    parsedVideo.parse()

    val videoSamples = parsedVideo.getSamples(true)




}





/* parsedAudio.parse()

    val outputFile= File("result.mp4")
    if (outputFile.exists()){
        println(outputFile.delete())
    }

    val muxer= DashedWriter(file = outputFile,listOf(parsedVideo,parsedAudio), progress = {progress,percent->
        println(progress)
    })
    muxer.build()

    val resultPraser= DashedParser(outputFile, doLogging = true)
    resultPraser.parse()*/