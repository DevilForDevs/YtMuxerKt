package muxer.mpfour.models.trakModels.edtsModels

data class ElstBox(
    val version: Int,
    val flags: Int,
    val entries: List<ElstEntry>
)