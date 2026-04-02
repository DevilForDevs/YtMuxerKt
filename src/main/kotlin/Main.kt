import muxer.webm.WebMParser
import muxer.webm.WebmMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile


fun main() {


    val rafvideo= RandomAccessFile(File("video_720p.webm"),"r")
    val rafAudio= RandomAccessFile(File("audio_251.opus"),"r")
    val videoParser= WebMParser(rafvideo,false,0L,rafvideo.length())
    val audioParser= WebMParser(rafAudio,false,0L,rafAudio.length())
    videoParser.parse()
    audioParser.parse()
    val otfile=File("muxedwebm.webm")
    if (otfile.exists()){
        otfile.delete()
    }
    val muxer= WebmMuxer(otfile,listOf(videoParser,audioParser), progress = {progress,percernt->
        println(progress)
    })
    muxer.writeSegment()
    val rafoutput= RandomAccessFile(otfile,"r")
    val parseOutput= WebMParser(rafoutput,true,0L,rafoutput.length())
    parseOutput.parse()

    debugUsingFffmpeg(otfile)

}


fun muxWithFFmpeg(videoFile: File, audioFile: File, outputFile: File) {
    try {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // Build the FFmpeg command
        val cmd = listOf(
            "ffmpeg",
            "-y",                     // overwrite if exists
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c", "copy",             // copy streams, no re-encoding
            "-map", "0:v:0",          // take video from first input
            "-map", "1:a:0",          // take audio from second input
            outputFile.absolutePath
        )

        // Run the command
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        // Print FFmpeg output to console
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { println(it) }
        }

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            println("Muxing completed successfully: ${outputFile.absolutePath}")
        } else {
            println("Muxing failed with exit code $exitCode")
        }

    } catch (e: IOException) {
        e.printStackTrace()
    } catch (e: InterruptedException) {
        e.printStackTrace()
    }
}

fun debugUsingFffmpeg(outputFile: File){
    val ffprobeCmd = listOf(
        "cmd", "/c", "start", "cmd", "/k",  // open new terminal and keep it open
        "ffprobe",
        "-hide_banner",
        "-v", "trace",
        outputFile.absolutePath
    )
    ProcessBuilder(ffprobeCmd)
        .directory(File(System.getProperty("user.dir"))) // optional working dir
        .start()
}




