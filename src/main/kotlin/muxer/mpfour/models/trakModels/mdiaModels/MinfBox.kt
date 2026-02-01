package muxer.mpfour.models.trakModels.mdiaModels

import muxer.mpfour.models.trakModels.mdiaModels.minfModels.DinfBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.StblBox

data class MinfBox(
    val mediaHandler: ByteArray,
    val dinfBox: DinfBox?,   // Always present (data reference info)
    val stblBox: StblBox     // Always present (sample table)
)