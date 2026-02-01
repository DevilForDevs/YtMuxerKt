package muxer.mpfour.moofBoxes

data class TfhdBox(
    val version: Int,
    val flags: Int,
    val trackId: Int,

    val baseDataOffset: Long? = null,
    val sampleDescriptionIndex: Int? = null,
    val defaultSampleDuration: Int? = null,
    val defaultSampleSize: Int? = null,
    val defaultSampleFlags: Int? = null,
)