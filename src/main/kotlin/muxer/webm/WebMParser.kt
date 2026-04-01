package muxer.webm
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.lang.Float
import kotlin.text.toString


class WebMParser(private val reader: RandomAccessFile,private val doLogging: Boolean,private val baseOffset: Long,private val baseEndOffset: Long) {

    private val containerIds = setOf(0x1654AE6BL, 0x18538067L, 0x1549A966L, 0x1C53BB6BL)
    var totalBlocks=0
    var firstClusterOffset=0L
    var firstClusterSize=0L
    var nextBlockOffset=0L
    var currentClusterTimeCode: Long?=null
    var nextBlockSize=0L
    var totalClusters=0


    val elementNames = mapOf(
        // ─── EBML Header ─────────────────────────────
        0x1A45DFA3L to "EBML",
        0x4286L to "EBMLVersion",
        0x42F7L to "EBMLReadVersion",
        0x42F2L to "EBMLMaxIDLength",
        0x42F3L to "EBMLMaxSizeLength",
        0x4282L to "DocType",
        0x4287L to "DocTypeVersion",
        0x4285L to "DocTypeReadVersion",

        // ─── Segment ────────────────────────────────
        0x18538067L to "Segment",
        0x114D9B74L to "SeekHead",
        0x4DBBL to "Seek",
        0x53ABL to "SeekID",
        0x53ACL to "SeekPosition",

        // ─── Info ───────────────────────────────────
        0x1549A966L to "Info",
        0x2AD7B1L to "TimecodeScale",
        0x4489L to "Duration",
        0x4D80L to "MuxingApp",
        0x5741L to "WritingApp",
        0x7BA9L to "Title",
        0x4461L to "DateUTC",
        0x73C4L to "SegmentUID",

        // ─── Tracks ─────────────────────────────────
        0x1654AE6BL to "Tracks",
        0xAEL to "TrackEntry",
        0xD7L to "TrackNumber",
        0x73C5L to "TrackUID",
        0x83L to "TrackType",
        0x5361L to "FlagEnabled",
        0x5366L to "FlagDefault",
        0x5364L to "FlagLacing",
        0x86L to "CodecID",
        0x258688L to "CodecName",
        0x63A2L to "CodecPrivate",
        0x22B59CL to "Language",
        0xE0L to "Video",
        0xE1L to "Audio",
        0xB0L to "PixelWidth",
        0xBA9L to "PixelHeight",
        0x9AL to "SamplingFrequency",
        0x9FL to "Channels",
        0x55AA to "FlagInterlaced",

        // ─── Clusters & Blocks ───────────────────────
        0x1F43B675L to "Cluster",
        0xE7L to "Timecode",
        0xA1L to "Block",
        0xA3L to "SimpleBlock",
        0xA0L to "BlockGroup",
        0x75A1L to "BlockAdditions",
        0xA6L to "BlockMore",
        0x9BL to "BlockAddID",
        0xFBL to "ReferenceBlock",
        0xFBL to "BlockDuration",
        0xABL to "BlockVirtual",

        // ─── Cues ───────────────────────────────────
        0x1C53BB6BL to "Cues",
        0xBBL to "CuePoint",
        0xB3L to "CueTime",
        0xB7L to "CueTrackPositions",
        0xF7L to "CueTrack",
        0xF1L to "CueClusterPosition",

        // ─── Chapters ───────────────────────────────
        0x1043A770L to "Chapters",
        0x45B9L to "EditionEntry",
        0x45CFL to "ChapterAtom",
        0x73C4L to "ChapterUID",
        0x91L to "ChapterTimeStart",
        0x92L to "ChapterTimeEnd",
        0x80L to "ChapterDisplay",

        // ─── Attachments ────────────────────────────
        0x1941A469L to "Attachments",
        0x61A7L to "AttachedFile",
        0x467EL to "FileName",
        0x4660L to "FileMimeType",
        0x465CUL to "FileData",

        // ─── Tags ───────────────────────────────────
        0x1254C367L to "Tags",
        0x7373L to "Tag",
        0x63C0L to "Targets",
        0x68CAL to "TargetTypeValue",
        0x68CAL to "TargetType",
        0x63C5L to "TagSimple",

        // ─── Misc ───────────────────────────────────
        0xBFL to "CRC-32",
        0xECL to "Void Element"
    )



    val ebmlInfo = EBMLInfo()
    val tracks = mutableListOf<TrackInfo>()
    val cueList = mutableListOf<CueEntry>()
    val clusters = mutableListOf<ClusterInfo>()

    var currentTrack: TrackInfo? = null
    var timecodeScale: Long = 1000000L   // Default 1ms units in nanoseconds
    var duration: Double = 0.0
    var muxingApp: String = ""
    var writingApp: String = ""

    val cueTimeList = mutableListOf<Long>()
    val cueTrackList = mutableListOf<Long>()
    val cueClusterPositionList = mutableListOf<Long>()

    fun parse() {
        parseElements(baseOffset, baseEndOffset)
       /* println(totalBlocks)*/
    }

    fun printHexFromFile(raf: RandomAccessFile, position: Long, length: Int) {
        val current = raf.filePointer

        val buffer = ByteArray(length)
        raf.seek(position)
        raf.readFully(buffer)

        buffer.forEachIndexed { i, b ->
            println(String.format("%03d : %02X", i, b))
        }

        raf.seek(current) // restore pointer
    }

    fun parseElements(startOffset: Long, endOffset: Long, depth: Int = 0) {
        var offset = startOffset
        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize
            val name = elementNames[id] ?: "Unknown"
            if (doLogging){
                val totalSize = headerSize + size
                if (name=="Unknown"){
                    println("Unknown Element with id: $id Payload size: $size ")
                }else{
                    println("Top Level Element: ${name} At: $offset  Payload,Header,Total Size :: ${convertBytes(size)}, ${convertBytes(totalSize-size)} ${convertBytes(totalSize)}")
                }
            }
            when (id) {

                0x114D9B74L -> {
                    parseSeekHead(contentOffset, contentOffset + size,doLogging)
                }

                // === Root Containers ===
                0x1A45DFA3L -> parseElements(contentOffset, contentOffset + size, depth + 1) // EBML
                0x18538067L -> parseElements(contentOffset, contentOffset + size, depth + 1) // Segment

                // === Info ===
                0x1549A966L -> parseElements(contentOffset, contentOffset + size, depth + 1) // Info
                0x2AD7B1L -> timecodeScale = readUInt(contentOffset, size) // TimecodeScale
                0x4489L -> duration = readFloat(contentOffset, size)       // Duration
                0x4D80L -> muxingApp = readString(contentOffset, size)     // MuxingApp
                0x5741L -> writingApp = readString(contentOffset, size)    // WritingApp

                // === Tracks ===
                0x1654AE6BL -> parseElements(contentOffset, contentOffset + size, depth + 1) // Tracks
                0xAEL -> parseTrackEntry(contentOffset, size)
                0xD7L -> currentTrack?.number = readUInt(contentOffset, size)
                0x83L -> currentTrack?.type = readUInt(contentOffset, size)
                0x86L -> currentTrack?.codecID = readString(contentOffset, size)
                0x258688L -> currentTrack?.codecName = readString(contentOffset, size)
                0x22B59CL -> currentTrack?.language = readString(contentOffset, size)
                0xE0L -> parseElements(contentOffset, contentOffset + size, depth + 1) // Video
                0xE1L -> parseElements(contentOffset, contentOffset + size, depth + 1) // Audio
                0xB0L -> currentTrack?.width = readUInt(contentOffset, size)
                0xBA9L -> currentTrack?.height = readUInt(contentOffset, size)
                0x9AL -> currentTrack?.samplingFrequency = readFloat(contentOffset, size)
                0x9FL -> currentTrack?.channels = readUInt(contentOffset, size)

                // === Cluster ===
                0x1F43B675L -> {
                    totalClusters++
                    if (firstClusterOffset == 0L) {
                        firstClusterOffset = contentOffset
                        firstClusterSize = size
                    }
                    val result = parseClusterInfo(contentOffset, size, reader, false)
                    totalBlocks += result.second
                    /*if (doLogging){
                        println("Total Blocks in this cluster: ${result.second} Abs T of this cluster: ${result.first}")
                    }*/
                }
                0xE7L -> currentClusterTimeCode = readUInt(contentOffset, size) // Timecode inside Cluster
                0xA3L, 0xA0L -> continue //not required now

                // === Cueing ===
                0x1C53BB6BL -> parseCues(contentOffset, contentOffset + size,doLogging)
                0xBBL -> parseElements(contentOffset, contentOffset + size, depth + 1) // CuePoint
                0xB3L -> cueTimeList.add(readUInt(contentOffset, size)) // CueTime
                0xB7L -> parseElements(contentOffset, contentOffset + size, depth + 1) // CueTrackPositions
                0xF7L -> cueTrackList.add(readUInt(contentOffset, size))
                0xF1L -> cueClusterPositionList.add(readUInt(contentOffset, size))

                // === Attachments / Tags / Chapters (optional) ===
                0x1941A469L, // Attachments
                0x1043A770L, // Chapters
                0x1254C367L  // Tags
                    -> parseElements(contentOffset, contentOffset + size, depth + 1)

                // === Default container fallback ===
                in containerIds -> parseElements(contentOffset, contentOffset + size, depth + 1)
            }

            offset = contentOffset + size
        }
    }

    fun readString(offset: Long, size: Long): String {
        reader.seek(offset)
        val bytes = ByteArray(size.toInt())
        reader.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    fun parseCues(startOffset: Long, endOffset: Long,doLogging: Boolean) {
        var offset = startOffset
        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize

            when (id) {
                0xBBL -> { // CuePoint
                    parseCuePoint(contentOffset, contentOffset + size,doLogging)
                }
            }

            offset = contentOffset + size
        }
    }

    fun parseCuePoint(startOffset: Long, endOffset: Long,doLogging: Boolean) {
        var offset = startOffset
        var cueTime: Long? = null
        val trackList = mutableListOf<Long>()
        val clusterPositions = mutableListOf<Long>()

        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize

            when (id) {
                0xB3L -> cueTime = readUInt(contentOffset, size) // CueTime
                0xB7L -> parseCueTrackPositions(contentOffset, contentOffset + size, trackList, clusterPositions)
            }

            offset = contentOffset + size
        }
        if (doLogging){
            // ✅ Log in one line after we’ve parsed the full CuePoint
            if (cueTime != null && trackList.isNotEmpty() && clusterPositions.isNotEmpty()) {
                for (i in trackList.indices) {
                    val track = trackList[i]
                    val clusterPos = clusterPositions.getOrNull(i) ?: -1L
                    println("CuePoint -> Time: $cueTime | Track: $track | ClusterPos: $clusterPos")
                }
            }
        }
    }

    fun parseCueTrackPositions(
        startOffset: Long,
        endOffset: Long,
        trackList: MutableList<Long>,
        clusterPosList: MutableList<Long>
    ) {
        var offset = startOffset
        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize

            when (id) {
                0xF7L -> trackList.add(readUInt(contentOffset, size))        // CueTrack
                0xF1L -> clusterPosList.add(readUInt(contentOffset, size))  // CueClusterPosition
            }

            offset = contentOffset + size
        }
    }


    fun parseSeekHead(startOffset: Long, endOffset: Long,doLogging: Boolean) {
        var offset = startOffset
        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize

            when (id) {
                0x4DBBL -> { // Seek
                    parseSeek(contentOffset, contentOffset + size,doLogging)
                }
            }
            offset = contentOffset + size
        }
    }

    fun parseSeek(startOffset: Long, endOffset: Long,doLogging: Boolean) {
        var offset = startOffset
        var seekID: Long? = null
        var seekPosition: Long? = null

        while (offset < endOffset && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val size = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize

            when (id) {
                0x53ABL -> seekID = readUInt(contentOffset, size) // SeekID
                0x53ACL -> seekPosition = readUInt(contentOffset, size) // SeekPosition
            }

            offset = contentOffset + size
        }
        if (doLogging){
            println("Seek Element: ${elementNames[seekID?:0]}  SeekPosition: $seekPosition")
        }

    }

    fun readUInt(offset: Long, size: Long): Long {
        reader.seek(offset)
        var value = 0L
        repeat(size.toInt()) {
            value = (value shl 8) or (reader.readUnsignedByte().toLong())
        }
        return value
    }

    fun readFloat(offset: Long, size: Long): Double {
        reader.seek(offset)
        return when (size.toInt()) {
            4 -> Float.intBitsToFloat(reader.readInt()).toDouble()
            8 -> java.lang.Double.longBitsToDouble(reader.readLong())
            else -> 0.0
        }
    }


    fun getBlock(doLogging: Boolean=false): BlockEntry? {
        if (firstClusterOffset == 0L || firstClusterSize == 0L) {
            println("parse source first")
            return null
        }

        if (currentClusterTimeCode == null) {
            val clscode = parseClusterInfo(firstClusterOffset, firstClusterSize, reader, false)
            currentClusterTimeCode = clscode.first
        }

        val clusterEnd = firstClusterOffset + firstClusterSize
        var offset = if (nextBlockOffset == 0L) firstClusterOffset else nextBlockOffset

        while (offset < clusterEnd) {
            val element = findNextElement(reader, offset) ?: break
            val id = element.id
            val size = element.contentSize
            val endOffset = element.endOffset

            if (id == 0xA3L || id == 0xA0L) { // SimpleBlock or BlockGroup
                reader.seek(element.contentOffset)
                val blockData = ByteArray(size.toInt())
                reader.readFully(blockData)
                val blockStream = ByteArrayInputStream(blockData)

                // --- Track number (VInt) ---
                val trackNumber = readVInt(blockStream)
                val trackVIntLength = trackNumber.second // assuming readVInt returns Pair(value, length)

                // --- Relative timecode (16-bit signed) ---
                val high = blockStream.read()
                val low = blockStream.read()
                val blockTimecode = ((high shl 8) or low).toShort().toLong()

                // --- Flags ---
                val flags = blockStream.read()
                val isKeyframe = flags and 0x80 != 0

                val absoluteTimecode = (currentClusterTimeCode ?: 0) + blockTimecode

                // --- Compute payload offset & size ---
                val frameAbsoluteOffset = element.contentOffset + trackVIntLength + 3 // track VInt + timecode(2) + flags(1)
                val frameSize = size - (trackVIntLength + 3)

                nextBlockOffset = endOffset
                nextBlockSize = size

                return BlockEntry(
                    trackNumber = trackNumber.first,
                    trackType = if (trackNumber.first == 1) "video" else "audio",
                    clusterTimecode = currentClusterTimeCode ?: 0,
                    blockTimecode = blockTimecode,
                    absoluteTimecode = absoluteTimecode,
                    isKeyframe = isKeyframe,
                    frameAbsoluteOffset = frameAbsoluteOffset,
                    frameSize = frameSize,
                    rf = reader
                )
            }

            // Unknown element, skip it
            offset = endOffset
        }

        // End of cluster
        nextBlockOffset = 0L
        nextBlockSize = 0L
        var nextOffset = clusterEnd
        var nextElement = findNextElement(reader, nextOffset)
        while (nextElement != null && nextElement.id != 0x1F43B675L) {
            if (doLogging){
                println("Skipping non-cluster element: id=${nextElement.id.toString(16)}")
            }
            nextOffset = nextElement.endOffset
            nextElement = findNextElement(reader, nextOffset)
        }
        if (nextElement != null && nextElement.id == 0x1F43B675L) {
            firstClusterOffset = nextElement.contentOffset
            firstClusterSize = nextElement.contentSize
            val newTimecode = parseClusterInfo(firstClusterOffset, firstClusterSize, reader, false)
            currentClusterTimeCode = newTimecode.first
            if (doLogging){
                println("Moved to next cluster: offset=$firstClusterOffset, size=$firstClusterSize, timecode=$currentClusterTimeCode")
            }
            // Continue parsing in the new cluster
            return getBlock() // Recursive call to process the new cluster
        } else {
           if (doLogging){
               println("No more clusters found")
           }
            return null
        }
    }



    fun findNextElement(reader: RandomAccessFile, startOffset: Long): ElementInfo? {
        val fileLength = reader.length()
        var offset = startOffset

        while (offset < fileLength) {
            reader.seek(offset)

            val id = readElementId(reader) ?: run {
                offset++
                continue
            }
            val size = readElementSize(reader) ?: run {
                offset++
                continue
            }

            val headerSize = reader.filePointer - offset
            val contentOffset = offset + headerSize
            val endOffset = contentOffset + size

            // Validate: size must be positive and within file bounds
            if (size >= 0 && endOffset <= fileLength) {
                return ElementInfo(id, contentOffset, size, endOffset)
            }

            // Otherwise, advance 1 byte and try again (to skip invalid/padded bytes)
            offset++
        }

        return null // No valid element found
    }

    /**
     * Parse a TrackEntry container. Populates `currentTrack` and appends it to `tracks`.
     * Uses the same readElementId/readElementSize/readString/readUInt/readFloat helpers you already have.
     */
    fun parseTrackEntry(contentOffset: Long, size: Long) {
        var offset = contentOffset
        val end = contentOffset + size
        // create a new track and set it as current
        currentTrack = TrackInfo()

        while (offset < end && offset < reader.length()) {
            reader.seek(offset)
            val id = readElementId(reader) ?: break
            val esize = readElementSize(reader) ?: break
            val headerSize = reader.filePointer - offset
            val payload = offset + headerSize

            when (id) {
                0xD7L -> currentTrack?.number = readUInt(payload, esize)          // TrackNumber
                0x83L -> currentTrack?.type = readUInt(payload, esize)            // TrackType
                0x86L -> currentTrack?.codecID = readString(payload, esize)       // CodecID
                0x258688L -> currentTrack?.codecName = readString(payload, esize) // CodecName
                0x22B59CL -> currentTrack?.language = readString(payload, esize)  // Language
                0x63A2L -> { // CodecPrivate
                    val data = ByteArray(esize.toInt())
                    reader.seek(payload)
                    reader.readFully(data)
                    currentTrack?.codecPrivate = data
                }

                // Video subcontainer
                0xE0L -> {
                    var vOff = payload
                    val vEnd = payload + esize
                    while (vOff < vEnd && vOff < reader.length()) {
                        reader.seek(vOff)
                        val vid = readElementId(reader) ?: break
                        val vs = readElementSize(reader) ?: break
                        val vh = reader.filePointer - vOff
                        val vp = vOff + vh
                        when (vid) {
                            0xB0L -> currentTrack?.width = readUInt(vp, vs)   // PixelWidth
                            0xBAL, 0xBA9L -> currentTrack?.height = readUInt(vp, vs) // PixelHeight
                        }
                        vOff = vp + vs
                    }
                }

                // Audio subcontainer
                0xE1L -> {
                    var aOff = payload
                    val aEnd = payload + esize
                    while (aOff < aEnd && aOff < reader.length()) {
                        reader.seek(aOff)
                        val aid = readElementId(reader) ?: break
                        val asz = readElementSize(reader) ?: break
                        val ah = reader.filePointer - aOff
                        val ap = aOff + ah
                        when (aid) {
                            0x9AL -> currentTrack?.samplingFrequency = readFloat(ap, asz)
                            0x9FL -> currentTrack?.channels = readUInt(ap, asz)
                        }
                        aOff = ap + asz
                    }
                }

                else -> {
                    // unknown/unused elements inside TrackEntry: skip
                }
            }

            offset = payload + esize
        }

        // finalize the track
        currentTrack?.let { tracks.add(it) }
        currentTrack = null
    }


    private fun readUnsignedInt(size: Long): Long {
        require(size in 1..8) { "Unexpected size for unsigned int: $size" }
        var value = 0L
        repeat(size.toInt()) {
            value = (value shl 8) or reader.readUnsignedByte().toLong()
        }
        return value
    }
    private fun readAscii(size: Long): String {
        require(size <= 1024) { "Unreasonably large ASCII element: $size" }
        val bytes = ByteArray(size.toInt())
        reader.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}













