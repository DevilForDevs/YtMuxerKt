package muxer.mpfour.models.trakModels

import muxer.mpfour.models.trakModels.mdiaModels.HdlrBox
import muxer.mpfour.models.trakModels.mdiaModels.MdhdBox
import muxer.mpfour.models.trakModels.mdiaModels.MinfBox

data class MdiaBox(
    val mdhdBox: MdhdBox,
    val hdlrBox: HdlrBox,
    val minfBox: MinfBox?
)