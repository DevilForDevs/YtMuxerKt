import mpfour.DashedParser
import java.io.File

fun main() {
    val video = File("ULAhmUlf5D0(136).mp4")
    val parsedVideo = DashedParser(video, false)
    parsedVideo.parse()
    var samps = parsedVideo.getSamples(true)

    var commputedSampleCount=0
    while (samps.isNotEmpty()){
        commputedSampleCount+=samps.size
        for (samp in samps){
            println("Sample offset: ${samp.frameAbsOffset} sample size: ${samp.frameSize} keyframe: ${samp.isSyncSample}")
        }
        samps=parsedVideo.getSamples(false)
    }
    println("Computed Sample Count: $commputedSampleCount   From parser: ${parsedVideo.trunEntries}")




}
