package mpfour

data class TrunSampleEntry(
    val index: Int,
    val size: Int,
    val offset: Long,
    val duration: Int? = null,
    val flags: Int? = null,
    val compositionTimeOffset: Int? = null,
    val isSyncSample: Boolean = true
)