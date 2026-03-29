package muxer.ytdownloaders


import java.io.FileOutputStream

fun printProgress(dbyt: String, percent: Int, speed: String) {
    val barLength = 30
    val filled = (percent * barLength) / 100
    val bar = "█".repeat(filled) + "-".repeat(barLength - filled)

    print("\r[$bar] $percent% | $dbyt | $speed")
    if (percent >= 100) println() // move to next line when done
}


fun ytVideoDownloader(){
    val playerResponse = getStreamingData("o9vEfB1GNio")
    val adaptiveFormats = playerResponse
        .getJSONObject("playerResponse")
        .getJSONObject("streamingData")
        .getJSONArray("adaptiveFormats")

    for (i in 0 until adaptiveFormats.length()) {
        val obj = adaptiveFormats.getJSONObject(i)
        val itag = obj.getInt("itag")

        // 🎧 Audio (Opus)
        if (itag == 251) {
            val fos = FileOutputStream("audio_251.opus")

            println("Downloading AUDIO (itag 251)...")

            downloader(
                url = obj.getString("url"),
                fos = fos,
                onDisk = 0L,
                totalBytes = obj.getString("contentLength").toLong(),
                isCancelled = { false },
                ::printProgress
            )
        }

        // 🎥 720p WebM video (VP9 usually)
        if (itag == 247) {
            val fos = FileOutputStream("video_720p.webm")

            println("Downloading VIDEO (itag 247 - 720p)...")

            downloader(
                url = obj.getString("url"),
                fos = fos,
                onDisk = 0L,
                totalBytes = obj.getString("contentLength").toLong(),
                isCancelled = { false },
                ::printProgress
            )
        }
    }
}