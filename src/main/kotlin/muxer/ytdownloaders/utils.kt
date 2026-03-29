package muxer.ytdownloaders

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import kotlin.math.roundToInt

fun downloader(
    url: String,
    fos: FileOutputStream,
    onDisk: Long,
    totalBytes: Long,
    isCancelled: () -> Boolean, // NEW
    progress: (dbyt: String, percent: Int, speed: String) -> Unit,
) {
    if (isCancelled()) {
        // Stop immediately before starting
        progress("Cancelled", 0, "0 KB/s")
        return
    }

    val dclient = OkHttpClient()
    val chunkSize = 9437184L // 9MB
    val start = onDisk
    val end = minOf(start + chunkSize - 1, totalBytes - 1)

    val request =
        Request
            .Builder()
            .url(url)
            .addHeader("Range", "bytes=$start-$end")
            .build()

    val response = dclient.newCall(request).execute()

    if (response.code == 206) {
        response.body!!.byteStream().use { inputStream ->
            val buffer = ByteArray(1024)
            var bytesRead: Int

            var downloadedInChunk = 0L
            var speedBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled()) {
                    // Stop download immediately
                    try {
                        fos.close()
                    } catch (_: Exception) {
                    }
                    progress("Cancelled", 0, "0 KB/s")
                    return
                }

                fos.write(buffer, 0, bytesRead)
                downloadedInChunk += bytesRead
                speedBytes += bytesRead

                val currentDownloaded = onDisk + downloadedInChunk
                val percent = ((currentDownloaded * 100) / totalBytes).toInt()
                val now = System.currentTimeMillis()

                if (now - lastTime >= 1000) {
                    val speedText = convertSpeed(speedBytes)
                    val pg = "${convertBytes2(currentDownloaded)}/${convertBytes2(totalBytes)}"
                    progress(pg, percent, speedText)
                    speedBytes = 0
                    lastTime = now
                }
            }

            // Final update after chunk
            val finalDownloaded = onDisk + downloadedInChunk
            val pg = "${convertBytes2(finalDownloaded)}/${convertBytes2(totalBytes)}"
            val percent = ((finalDownloaded * 100) / totalBytes).toInt()
            progress(pg, percent, convertSpeed(speedBytes))

            // Continue next chunk if needed
            if (finalDownloaded < totalBytes && !isCancelled()) {
               downloader(url, fos, finalDownloaded, totalBytes, isCancelled, progress)
            }
        }
    } else {
        println("HTTP error: Expected 206 Partial Content, got ${response.code}")
    }
}

fun convertBytes2(sizeInBytes: Long): String {
    val kilobyte = 1024
    val megabyte = kilobyte * 1024
    val gigabyte = megabyte * 1024

    return when {
        sizeInBytes >= gigabyte -> "${(sizeInBytes.toDouble() / gigabyte).roundToInt()} GB"
        sizeInBytes >= megabyte -> "${(sizeInBytes.toDouble() / megabyte).roundToInt()} MB"
        sizeInBytes >= kilobyte -> "${(sizeInBytes.toDouble() / kilobyte).roundToInt()} KB"
        else -> "$sizeInBytes Bytes"
    }
}

fun convertSpeed(bytesPerSec: Long): String {
    val kilobyte = 1024.0
    val megabyte = kilobyte * 1024
    val gigabyte = megabyte * 1024

    return when {
        bytesPerSec >= gigabyte -> "${(bytesPerSec / gigabyte).roundToInt()} GB/s"
        bytesPerSec >= megabyte -> "${(bytesPerSec / megabyte).roundToInt()} MB/s"
        bytesPerSec >= kilobyte -> "${(bytesPerSec / kilobyte).roundToInt()} KB/s"
        else -> "$bytesPerSec B/s"
    }
}
