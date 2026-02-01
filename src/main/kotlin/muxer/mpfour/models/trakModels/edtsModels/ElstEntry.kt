package muxer.mpfour.models.trakModels.edtsModels

data class ElstEntry(
    val segmentDuration: Long,   // duration of this edit segment (in movie timescale units)
    val mediaTime: Long,         // starting time within the media; -1 means empty edit
    val mediaRate: Double        // playback rate for this segment (typically 1.0)
)