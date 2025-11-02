import mpfour.DashedParser
import java.io.File

fun main() {
    val video = File("ULAhmUlf5D0(136).mp4")
    val parsedVideo = DashedParser(video, false)
    parsedVideo.parse()

    var videoSamples=parsedVideo.getSamples(true)

    while (videoSamples.isNotEmpty()){
        for (samp in videoSamples){
            println("Sample Offset Abs: ${samp.frameAbsOffset} Sample Size: ${samp.frameSize}  Keysampe: ${samp.isSyncSample}")
        }
        videoSamples=parsedVideo.getSamples(false)
    }





}
