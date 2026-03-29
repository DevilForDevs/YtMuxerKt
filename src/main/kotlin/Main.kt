import muxer.mpfour.DashedParser
import muxer.mpfour.DashedWriter
import muxer.webm.WebMParser
import muxer.webm.WebmMuxer
import muxer.ytdownloaders.downloader
import muxer.ytdownloaders.getStreamingData
import java.io.File
import java.io.FileOutputStream
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



