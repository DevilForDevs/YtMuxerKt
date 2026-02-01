import muxer.mpfour.DashedParser
import muxer.mpfour.DashedWriter
import java.io.File
import java.io.RandomAccessFile

fun main() {
    val videoFile= File("yj4bnTrqwvA(136).mp4")
    val audioFile= File("yj4bnTrqwvA(136).mp3")

    val videoRaf= RandomAccessFile(videoFile,"r")
    val audioRaf= RandomAccessFile(audioFile,"r")
    val videoParser= DashedParser(videoRaf,false,0,videoFile.length())
    val audioParser= DashedParser(audioRaf,false,0,audioFile.length())
    val outputFile= File("moutouputonsamefile.mp4")
    val output= RandomAccessFile(outputFile,"rw")


    val startNs = System.nanoTime()

    fun formatTime(ms: Long): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%03d".format(minutes, seconds, millis)
    }
    println("muxing")

    val muxer = DashedWriter(
        output = output,
        startOffset = 0,
        sources = listOf(videoParser, audioParser) as MutableList<DashedParser>,

        sampleWritten = {



        }
    )

    muxer.buildNonFmp4()
    val endNs = System.nanoTime()

    val totalMs = (endNs - startNs) / 1_000_000

    // Print start, end, and total duration
    println("\n======= MERGE SUMMARY =======")
    println("Start Time : $startNs ns")
    println("End Time   : $endNs ns")
    println("Total Time : ${formatTime(totalMs)} (mm:ss:ms)")
    println("==============================")


}

fun muxAudioVideo(
    videoPath: String,
    audioPath: String,
    outputPath: String
): Boolean {
    try {
        val command = listOf(
            "ffmpeg",
            "-y",                   // overwrite output
            "-i", videoPath,        // input video
            "-i", audioPath,        // input audio
            "-c:v", "copy",         // copy video
            "-c:a", "copy",         // copy audio
            "-movflags", "+faststart", // *** enable fast start ***
            outputPath
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        reader.lines().forEach { println(it) }

        val exitCode = process.waitFor()
        return exitCode == 0

    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}
