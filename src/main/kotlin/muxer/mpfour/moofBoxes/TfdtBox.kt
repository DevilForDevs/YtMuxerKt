package muxer.mpfour.moofBoxes

data class TfdtBox(
    val version: Int,
    val flags: Int,
    val baseMediaDecodeTime: Long,
)