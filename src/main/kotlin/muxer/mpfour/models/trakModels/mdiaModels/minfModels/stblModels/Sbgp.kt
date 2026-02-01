package muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels

import muxer.mpfour.models.BoxInfo

data class Sbgp (
    val boxOffset: Long,
    val boxSize: Long,
): BoxInfo(boxOffset,boxSize)