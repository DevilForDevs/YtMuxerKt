package muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels

import muxer.mpfour.models.BoxInfo

data class Sgpd (
    val boxOffset: Long,
    val boxSize: Long,
): BoxInfo(boxOffset,boxSize)