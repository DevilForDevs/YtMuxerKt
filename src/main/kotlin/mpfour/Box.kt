package org.ytmuxer.mpfour
data class Box(
    var type: String = "",
    var offset: Long = 0,
    var size: Long = 0
)