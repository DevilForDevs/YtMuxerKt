package muxer.mpfour.models

open class BoxInfo(
    val offset: Long,   // absolute file offset (start of box)
    val size: Long
)