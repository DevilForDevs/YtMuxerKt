package mpfour

data class TrunSampleEntry(
    val frameSize: Int,
    val frameAbsOffset: Long,
    val duration: Int? = null,
    val flags: Int? = null,
    val compositionTimeOffset: Int? = null,
    val isSyncSample: Boolean = true,
)