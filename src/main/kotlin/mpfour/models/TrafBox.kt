package mpfour.models

data class TrafBox(
    val truns: MutableList<TrunBox>,
    val tfhdBox: TfhdBox,
    val tfdtBox: TfdtBox,
)