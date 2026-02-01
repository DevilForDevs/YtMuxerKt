package muxer.mpfour.models.trakModels.mdiaModels.minfModels

import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Co64
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Ctts
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sbgp
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sgpd
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stco
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsc
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.StsdBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stss
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsz
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stts
data class StblBox(
    val stsdBox: StsdBox,
    val stts: Stts,
    val stsc: Stsc,
    val stsz: Stsz,
    val stss: Stss,
    val stco: Stco?,   // nullable
    val co64: Co64?,   // nullable
    val ctts: Ctts?,
    val sgpd: Sgpd?,
    val sbgp: Sbgp?
)
