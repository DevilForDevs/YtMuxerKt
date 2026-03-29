package muxer.ytdownloaders

import muxer.ytdownloaders.RandomStringGenerator.generateContentPlaybackNonce
import muxer.ytdownloaders.RandomStringGenerator.generateTParameter
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

fun androidPlayerResponse(
    cpn: String,
    visitorData: String,
    videoId: String,
    t: String
): Request {

    val url =
        "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false&t=$t&id=$videoId&\$fields=playerResponse"

    val jsonBody = JSONObject().apply {

        put("context", JSONObject().apply {

            put("client", JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "21.03.36")
                put("clientScreen", "WATCH")

                put("platform", "MOBILE")
                put("osName", "Android")
                put("osVersion", "16")
                put("androidSdkVersion", 36)

                put("hl", "en-GB")
                put("gl", "GB")
                put("utcOffsetMinutes", 0)

                put("visitorData", visitorData)
            })

            put("request", JSONObject().apply {
                put("internalExperimentFlags", JSONArray())
                put("useSsl", true)
            })

            put("user", JSONObject().apply {
                put("lockedSafetyMode", false)
            })
        })

        put("playerRequest", JSONObject().apply {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        })

        put("disablePlayerResponse", false)
    }

    val headers = mapOf(
        "User-Agent" to "com.google.android.youtube/21.03.36 (Linux; U; Android 15; GB) gzip",
        "X-Goog-Api-Format-Version" to "2",
        "Content-Type" to "application/json",
        "Accept-Language" to "en-GB, en;q=0.9"
    )

    val body = jsonBody.toString()
        .toRequestBody("application/json".toMediaTypeOrNull())

    val builder = Request.Builder()
        .url(url)
        .post(body)

    headers.forEach { (k, v) -> builder.addHeader(k, v) }

    return builder.build()
}

fun getVisitorId(): String {

    val client = OkHttpClient()

    val url =
        "https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false"

    val jsonBody = JSONObject().apply {

        put("context", JSONObject().apply {

            put("client", JSONObject().apply {

                put("clientName", "ANDROID")
                put("clientVersion", "21.03.36")

                put("clientScreen", "WATCH")
                put("platform", "MOBILE")

                put("osName", "Android")
                put("osVersion", "16")
                put("androidSdkVersion", 36)

                put("hl", "en-GB")
                put("gl", "GB")
                put("utcOffsetMinutes", 0)
            })

            put("request", JSONObject().apply {
                put("internalExperimentFlags", JSONArray())
                put("useSsl", true)
            })

            put("user", JSONObject().apply {
                put("lockedSafetyMode", false)
            })
        })
    }

    val headers = mapOf(
        "User-Agent" to "com.google.android.youtube/21.03.36 (Linux; U; Android 15; GB) gzip",
        "X-Goog-Api-Format-Version" to "2",
        "Content-Type" to "application/json",
        "Accept-Language" to "en-GB, en;q=0.9"
    )

    val body =
        jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())

    val builder = Request.Builder()
        .url(url)
        .post(body)

    headers.forEach { (k, v) -> builder.addHeader(k, v) }

    val response = client.newCall(builder.build()).execute()

    val json = JSONObject(response.body!!.string())

    return json
        .getJSONObject("responseContext")
        .getString("visitorData")
}

fun getStreamingData(videoId: String): JSONObject {
    val requestResponse = JSONObject()
    val cpn = generateContentPlaybackNonce()
    val tp = generateTParameter()
    val visitorData = getVisitorId()
    val request = androidPlayerResponse(cpn, visitorData, videoId, tp)
    val client = OkHttpClient()


    try {
        val response = client.newCall(request).execute()
        if (response.code == 200) {
            val responseString = response.body?.string()
            return JSONObject(responseString)
        } else {

            requestResponse.put("error", "Returning fail: HTTP ${response.code}")
        }
    } catch (e: Exception) {

        requestResponse.put("error", e.message ?: "Unknown error")
    }
    return JSONObject()
}

fun getUrlByItag(adaptiveFormats: JSONArray, itag: Int): String? {
    for (i in 0 until adaptiveFormats.length()) {
        val format = adaptiveFormats.getJSONObject(i)

        if (format.optInt("itag") == itag) {

            // direct url case
            val url = format.optString("url")
            if (url.isNotEmpty()) {
                return url
            }

            // cipher case (url inside signatureCipher)
            val cipher = format.optString("signatureCipher")
            if (cipher.isNotEmpty()) {
                val params = cipher.split("&")
                for (p in params) {
                    if (p.startsWith("url=")) {
                        return URLDecoder.decode(
                            p.removePrefix("url="),
                            "UTF-8"
                        )
                    }
                }
            }
        }
    }

    return null
}
