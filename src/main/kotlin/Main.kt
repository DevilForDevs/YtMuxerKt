package org.ytmuxer

import org.ytmuxer.mpfour.AddPosterMp3
import org.ytmuxer.mpfour.DashedParser
import org.ytmuxer.mpfour.DashedWriter
import org.ytmuxer.webm.WebMParser
import org.ytmuxer.webm.WebmMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile


fun main() {
    /*val audio = File("Q1sfGVKJTXQ(136).mp3")*/
    /*val audioFile = File("7PQVnRy5UAs(248).mp3")

// Append audio to existing video
    FileOutputStream(videoFile, true).use { output ->
        FileInputStream(audioFile).use { input ->
            input.copyTo(output)
        }
    }

    val videoLength = videoFile.length() - audioFile.length() // original video size

    RandomAccessFile(videoFile, "r").use { raf ->
        val videoParser = WebMParser(raf, false, 0, videoLength)
        videoParser.parse()

        val audioParser = WebMParser(raf, false, videoLength, videoFile.length())
        audioParser.parse()

        val muxer = WebmMuxer(File("output.mp4"), listOf(videoParser, audioParser)) { progress, percent ->
            println("$progress  $percent%")
        }
        muxer.writeSegment()
    }

    videoFile.delete()
    audioFile.delete()*/


    val output = File("001 PYAR JUTA HE.mp3")
    val outpu = RandomAccessFile(output, "r")

    fun detectContainer(reader: RandomAccessFile): String {
        reader.seek(0)
        val header = ByteArray(12)
        reader.readFully(header)

        return when {
            header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte() -> "mp3"

            header.sliceArray(0..3).toString(Charsets.US_ASCII) == "RIFF" &&
                    header.sliceArray(8..11).toString(Charsets.US_ASCII) == "WAVE" -> "wav"

            header.sliceArray(0..3).toString(Charsets.US_ASCII) == "fLaC" -> "flac"

            header[0] == 0x1A.toByte() &&
                    header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() &&
                    header[3] == 0xA3.toByte() -> "webm/mkv"

            header.sliceArray(4..7).toString(Charsets.US_ASCII) == "ftyp" -> "mp4/mov/m4a"

            header.sliceArray(0..2).toString(Charsets.US_ASCII) == "FLV" -> "flv"

            header[0] == 0x00.toByte() &&
                    header[1] == 0x00.toByte() &&
                    header[2] == 0x01.toByte() &&
                    header[3] == 0xBA.toByte() -> "mpeg-ps"

            header[0] == 0x47.toByte() -> "mpeg-ts"

            else -> "unknown"
        }
    }


    val format = detectContainer(outpu)
    println("Detected format: $format")

    /*val outPutParser= DashedParser(outpu,true,0,output.length())
    outPutParser.parse()*/


}