package muxer.mpfour.models.trakModels.mdiaModels

data class MdhdBox(
    val version: Int,
    val creationTime: Long,
    val modificationTime: Long,
    val timescale: Long,
    val duration: Long,
    val language: String,
    val raw: ByteArray
)