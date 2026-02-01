package muxer.mpfour.moofBoxes

import muxer.mpfour.models.BoxInfo

data class MoofBox(
    val totalEntries: Int,
    var entriesRead: Int,
    val boxOffset: Long,
    val boxSize: Long,
): BoxInfo(boxOffset, boxSize)