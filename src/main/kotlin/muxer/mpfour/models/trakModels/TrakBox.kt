package muxer.mpfour.models.trakModels

data class TrakBox(
    val tkhdBox: Tkhd,
    val mdiaBox: MdiaBox,
    val edtsBox: EdtsBox? = null
)