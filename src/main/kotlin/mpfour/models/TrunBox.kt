package mpfour.models

data class TrunBox(
    val version: Int,
    val flags: Int,
    val totalSampleCount: Int,
    val dataOffset: Int? = null,
    val firstSampleFlags: Int? = null,
    var entriesOffset: Long,
    var sampleOffset: Long,
    val trunEndOffset: Long,
)