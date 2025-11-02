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

    var videoSamples=parsedVideo.getSamples(true)

    while (videoSamples.isNotEmpty()){
        for (samp in videoSamples.take(10)){
            println("Sample Offset Abs: ${samp.frameAbsOffset} Sample Size: ${samp.frameSize}  Keysampe: ${samp.isSyncSample}")
        }
        videoSamples=parsedVideo.getSamples(false)
    }
    println(parsedVideo.totalSamplesFromMoof)




}
