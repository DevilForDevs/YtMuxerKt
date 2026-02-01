package muxer.mpfour.models

import muxer.mpfour.models.trakModels.TrakBox

data class MoovBox(
    val mvhdBox: Mvhd,
    val trakBoxes: List<TrakBox>
)