package mpfour.models

data class TfdtBox(
    val version: Int,
    val flags: Int,
    val baseMediaDecodeTime: Long,
)