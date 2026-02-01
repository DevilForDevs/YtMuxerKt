package muxer.mpfour.moofBoxes

data class TrafBox(
    val truns: MutableList<TrunBox>,
    val tfhdBox: TfhdBox,
    val tfdtBox: TfdtBox,
)