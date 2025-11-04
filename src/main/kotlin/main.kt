import mpfour.DashedParser
import mpfour.DashedWriter
import java.io.File

fun main() {
    val video = File("fixed.mp4")

    val videoParser= DashedParser(video,true)
    videoParser.parse()


}
