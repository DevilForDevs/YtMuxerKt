package muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels

import muxer.mpfour.models.BoxInfo

data class Stts(
    val boxOffset: Long,
    val boxSize: Long,
    val raw: ByteArray
): BoxInfo(boxOffset, boxSize)