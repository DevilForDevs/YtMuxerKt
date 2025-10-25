package org.ytmuxer

import org.ytmuxer.mpfour.DashedParser
import org.ytmuxer.mpfour.DashedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile


fun main() {
    val videoFile = File("Q1sfGVKJTXQ(136).mp4")
    val audioFile = File("Q1sfGVKJTXQ(136).mp3")

// Append audio to existing video
    FileOutputStream(videoFile, true).use { output ->
        FileInputStream(audioFile).use { input ->
            input.copyTo(output)
        }
    }

    val videoLength = videoFile.length() - audioFile.length() // original video size

    RandomAccessFile(videoFile, "r").use { raf ->
        val videoParser = DashedParser(raf, false, 0, videoLength)
        videoParser.parse()

        val audioParser = DashedParser(raf, false, videoLength, videoFile.length())
        audioParser.parse()

        val muxer = DashedWriter(File("output.mp4"), listOf(videoParser, audioParser)) { progress, percent ->
            println("$progress  $percent%")
        }
        muxer.buildNonFMp4()
    }

    videoFile.delete()
    audioFile.delete()


}