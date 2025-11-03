import mpfour.DashedParser
import java.io.File

fun main() {
    val video = File("ULAhmUlf5D0(136).mp4")
    val parsedVideo = DashedParser(video, false)
    parsedVideo.parse()

    var samps = parsedVideo.getSamples(true)



    while (samps.isNotEmpty()){
        for (samp in samps){
            println("Sample Offset: ${samp.frameAbsOffset}  Size: ${samp.frameSize}  Keyframe: ${samp.isSyncSample}")
        }
        samps=parsedVideo.getSamples(false)
    }


}
