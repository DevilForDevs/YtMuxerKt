package muxer.mpfour.models

data class BoxHeader(
    val type: String,
    val size: Long,
    val startOffset: Long,
    val payloadOffset: Long,
    val payloadSize: Long
)