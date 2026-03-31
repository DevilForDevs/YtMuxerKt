package muxer.webm



object CueSizeEstimator {

    /** Estimate VINT size in bytes for a given value */
    fun vintSize(value: Long): Int {
        return when {
            value < 0x7F -> 1
            value < 0x3FFF -> 2
            value < 0x1FFFFF -> 3
            value < 0x0FFFFFFF -> 4
            value < 0x07FFFFFFFF -> 5
            value < 0x03FFFFFFFFFF -> 6
            value < 0x01FFFFFFFFFFFF -> 7
            else -> 8
        }
    }

    /**
     * Estimate size of a single CuePoint.
     * cueTime and clusterOffset are passed to estimate VINT size correctly.
     */
    fun estimateCuePointSize(cueTime: Long, clusterOffset: Long): Int {
        // CueTime element
        val cueTimeDataSize = 4 // assuming 32-bit timecode
        val cueTimeSize = 1 + vintSize(cueTimeDataSize.toLong()) + cueTimeDataSize

        // CueTrackPositions element
        val cueTrackDataSize = 1 // track number
        val cueTrackSize = 1 + vintSize(cueTrackDataSize.toLong()) + cueTrackDataSize

        val cueClusterDataSize = 4 // assuming 32-bit offset
        val cueClusterSize = 1 + vintSize(cueClusterDataSize.toLong()) + cueClusterDataSize

        val cueTrackPositionsData = cueTrackSize + cueClusterSize
        val cueTrackPositionsSize = 1 + vintSize(cueTrackPositionsData.toLong()) + cueTrackPositionsData

        // CuePoint container
        val cuePointData = cueTimeSize + cueTrackPositionsSize
        val cuePointSize = 1 + vintSize(cuePointData.toLong()) + cuePointData

        return cuePointSize
    }

    /** Estimate total Cues element size for a list of cues */
    fun estimateCuesSize(cueTimes: List<Long>, clusterOffsets: List<Long>): Int {
        require(cueTimes.size == clusterOffsets.size) { "cueTimes and clusterOffsets must match" }

        var totalCuePointsSize = 0
        for (i in cueTimes.indices) {
            totalCuePointsSize += estimateCuePointSize(cueTimes[i], clusterOffsets[i])
        }

        // Cues element container
        val cuesSize = 1 + vintSize(totalCuePointsSize.toLong()) + totalCuePointsSize
        return cuesSize
    }
}