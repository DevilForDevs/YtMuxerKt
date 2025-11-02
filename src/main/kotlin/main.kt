import mpfour.DashedParser
import java.io.File

fun main() {
    val video = File("ULAhmUlf5D0(136).mp4")
    val parsedVideo = DashedParser(video, false)
    parsedVideo.parse()

    parsedVideo.getSamples(true)





}
