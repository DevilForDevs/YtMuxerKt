package muxer.mpfour.models.trakModels

data class Tkhd(
    val creationTime: Long,
    val modificationTime: Long,
    val trackId: Int,
    val duration: Long,
    val layer: Int,          // typically 0 for base layer
    val volume: Float,       // 1.0 for audio, 0.0 for video
    val widthPixels: Int,
    val heightPixels: Int,
    val raw: ByteArray
)