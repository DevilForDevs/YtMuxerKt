package muxer.mpfour.models.trakModels

import muxer.mpfour.models.trakModels.edtsModels.ElstBox

data class EdtsBox(
    val elstBox: ElstBox?,
    val raw: ByteArray
)