package muxer.mpfour.models

data class BrandInfo(
    val majorBrand: String,
    val minorVersion: Int,
    val compatibleBrands: List<String>
)