package muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels

import muxer.mpfour.models.BoxInfo

data class StsdBox(
    val boxOffset: Long,
    val boxSize: Long,
    val raw: ByteArray? = null,
    val avcC: ByteArray
): BoxInfo(boxOffset, boxSize)
