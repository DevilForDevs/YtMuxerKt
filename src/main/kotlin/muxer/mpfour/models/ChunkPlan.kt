package muxer.mpfour.models

data class ChunkPlan(
    val firstChunkSamples: Int,
    val middleChunkSamples: Int,
    val middleChunkCount: Int,
    val lastChunkSamples: Int,
    val chunkCount: Int
)