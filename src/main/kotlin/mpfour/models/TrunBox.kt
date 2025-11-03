package mpfour.models

data class TrunBox(
    val version: Int,
    val flags: Int,
    val totalSampleCount: Int,
    val dataOffset: Int?,
    val firstSampleFlags: Int?,
    val entriesOffset: Long,           // original start of entries
    val sampleOffsetBase: Long,        // mdat offset
    val sampleSizes: IntArray?,        // precomputed sizes (if present)
    val cumulativeSizes: LongArray?
)