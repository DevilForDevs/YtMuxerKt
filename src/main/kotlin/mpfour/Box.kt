package mpfour
// Base class for common box metadata
open class BoxInfo(
    val offset: Long,   // absolute file offset (start of box)
    val size: Long
)