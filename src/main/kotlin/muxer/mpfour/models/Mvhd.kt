package muxer.mpfour.models

data class Mvhd(
    val timeScale: Int,
    val duration: Long,
    var raw: ByteArray
)