package muxer.mpfour.models.trakModels.mdiaModels

data class HdlrBox(
    val handlerType: String,
    val handlerName: String,
    val raw: ByteArray
)