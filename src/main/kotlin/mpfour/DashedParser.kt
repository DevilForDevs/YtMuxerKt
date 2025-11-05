package mpfour
import org.ytmuxer.webm.convertBytes
import java.io.File
import java.io.RandomAccessFile


class DashedParser(file: File,val doLogging: Boolean){
    val reader= RandomAccessFile(file,"r")

    //========moov boxex===========
    var mvhd: Mvhd?=null


    var mediaTimescale: Int = -1
    var mediaDuration: Long = -1
    var language: String = ""

    var trackDuration=-1L

    var videoInfo = VideoTrackInfo()


    // From hdlr
    var handlerType: String = ""

    var stsdBox: ByteArray? =null

    /**
     * This is total count of entries/samples
     * found in different truns of different moofs
     * used for tracking progress by dashed writter
     */
    var trunEntries=0

    val moofsList=mutableListOf<MoofBox>()
    var currentMoofBox: MoofParser?=null


    //fields updated from dashedWriter
    val keySamplesIndices=mutableListOf<Int>()
    val chunksOffsets=mutableListOf<Long>()
    val samplesSizes=mutableListOf<Int>()
    val cttsEntries = mutableListOf<Pair<Int, Int>>()

    var lastOffset: Int? = null
    var runLength = 0
    var initialChunk=true
    val samplesPerChunkList = mutableListOf<Int>()

    fun parse() {
        reader.seek(0)

        // --- Check ISO base media header (ftyp or styp) ---
        if (reader.length() < 8) {
            throw IllegalStateException("Invalid container: file too small to be a valid ISO file.")
        }

        val startOffset = reader.filePointer
        val size = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val boxType = String(typeBytes, Charsets.US_ASCII)

        if (boxType != "ftyp" && boxType != "styp") {
            throw IllegalStateException("Invalid container: expected 'ftyp' or 'styp' at offset 0, found '$boxType'")
        }

        if (doLogging) {
            println("Valid ISO header found: '$boxType' at offset $startOffset (size=$size)")
        }

        // --- Move back to start to re-read all boxes ---
        reader.seek(0)

        // --- Main parsing loop ---
        while (reader.filePointer + 8 <= reader.length()) {
            val startOffsetLoop = reader.filePointer
            val sizeLoop = reader.readInt().toLong()
            if (sizeLoop < 8) {
                throw IllegalStateException("Invalid box size $sizeLoop at offset $startOffsetLoop")
            }

            val typeBytesLoop = ByteArray(4).also { reader.readFully(it) }
            val boxTypeLoop = String(typeBytesLoop, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = sizeLoop - 8

            if (doLogging) {
                println(
                    "Box: $boxTypeLoop | BoxOffset: $startOffsetLoop | BoxSize: $sizeLoop | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (boxTypeLoop) {
                "moov" -> {
                    parseMoov(payloadOffset,payloadSize)  //moves fwd
                    reader.seek(payloadOffset + payloadSize)   //🟡 important line moves backward
                }

                "moof" -> {
                    val entriesInThisMoof = countEntriesInMoof(reader,payloadOffset, payloadSize)
                    trunEntries += entriesInThisMoof
                    moofsList.add(
                        MoofBox(
                            boxOffset = payloadOffset,
                            boxSize = payloadSize,
                            totalEntries = entriesInThisMoof,
                            entriesRead = 0
                        )
                    )
                    // Move reader to end of moof
                    reader.seek(payloadOffset + payloadSize) //🟡 important line pointer moves backward
                }

                else -> {
                    // Skip unknown or irrelevant boxes
                    reader.seek(payloadOffset + payloadSize)
                }
            }
        }
    }

    fun parseMoov(moovOffset: Long, moovSize: Long) {
        reader.seek(moovOffset)
        val moovEnd = moovOffset + moovSize
        while (reader.filePointer + 8 <= moovEnd) {
            val boxStart = reader.filePointer
            val size = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = size - 8

            if (size < 8 || boxStart + size > moovEnd) {
                if (doLogging) println("Invalid sub-box in moov: $type at offset=$boxStart size=$size")
                break
            }
            if (doLogging){
                println(
                    "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (type) {
                "trak" -> {
                    parseTrak(payloadOffset,payloadSize)
                    reader.seek(payloadOffset + payloadSize)  //🟡 important line pointer moves backward
                }
                "mvhd" -> {
                     mvhd=parseMvhd(reader,payloadOffset)
                     reader.seek(payloadOffset + payloadSize)  //🟡 important line pointer moves backward
                }
                else -> {
                    // Skip unknown box
                    reader.seek(payloadOffset + payloadSize)
                }
            }
        }
    }

    fun parseTrak(trakOffset: Long, trakSize: Long) {
        reader.seek(trakOffset)
        val trakEnd = trakOffset + trakSize

        while (reader.filePointer + 8 <= trakEnd) {
            val boxStart = reader.filePointer
            val size = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = size - 8

            if (size < 8 || boxStart + size > trakEnd) {
                if (doLogging) println("⚠ Invalid sub-box in trak: $type at offset=$boxStart size=$size")
                break
            }

            if (doLogging){
                println(
                    "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (type) {
                "tkhd" -> {
                    parseTkhd(reader,payloadOffset)
                    reader.seek(payloadOffset + payloadSize)  //🟡 important line pointer moves backward
                }
                "mdia" -> {
                    parseMdia(payloadOffset,payloadSize)
                    reader.seek(payloadOffset + payloadSize)
                }
                "edts"->{
                    parseEdts(reader,doLogging,payloadOffset,payloadSize)
                    reader.seek(payloadOffset + payloadSize)   //🟡 important line pointer moves backward
                }
                else -> {
                    // Skip unrecognized boxes
                    reader.seek(payloadOffset + payloadSize)
                }
            }
        }
    }

    fun parseMdia(offset: Long, size: Long) {
        val mdiaEnd = offset + size
        reader.seek(offset)

        while (reader.filePointer + 8 <= mdiaEnd) {
            val start = reader.filePointer
            val boxSize = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = boxSize - 8

            if (doLogging){
                println(
                    "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (type) {
                "minf" -> {
                    parseMinf(payloadOffset, payloadSize)  //currently ignore this
                    reader.seek(payloadOffset + payloadSize)
                }
                "mdhd"->{
                    parseMdhd(reader,payloadOffset)
                    reader.seek(payloadOffset + payloadSize)
                }
                "hdlr"->{
                    parseHdlr(reader,payloadOffset,payloadSize)
                    reader.seek(payloadOffset + payloadSize)
                }
                else -> reader.seek(payloadOffset + payloadSize)
            }
        }
    }

    fun parseMinf(offset: Long, size: Long) {
        val minfEnd = offset + size
        reader.seek(offset)

        while (reader.filePointer + 8 <= minfEnd) {
            val start = reader.filePointer
            val boxSize = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = boxSize - 8

            if (doLogging){
                println(
                    "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                            "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                )
            }

            when (type) {
                "vmhd" -> {
                    val vmhd = parseVmhd(reader, payloadOffset)
                    reader.seek(payloadOffset + payloadSize)
                }
                "smhd" -> {
                    val smhd = parseSmhd(reader, payloadOffset)
                    reader.seek(payloadOffset + payloadSize)
                }
                "dinf" -> {
                    val dinf = parseDinf(reader, payloadOffset, payloadSize)
                    reader.seek(payloadOffset + payloadSize)
                }
                "stbl" -> {
                    parseStbl(payloadOffset,payloadSize)
                    reader.seek(payloadOffset + payloadSize)
                }
                else -> reader.seek(payloadOffset + payloadSize)
            }

        }
    }

    fun parseStbl(offset: Long, size: Long){
        val stblEnd = offset + size
        reader.seek(offset)

        while (reader.filePointer + 8 <= stblEnd) {
            val boxStart = reader.filePointer
            val boxSize = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val payloadOffset = reader.filePointer
            val payloadSize = boxSize - 8

            if (doLogging) {
                if (doLogging){
                    println(
                        "Box: $type | BoxOffset: $payloadOffset | BoxSize: $payloadSize | " +
                                "PayloadOffset: $payloadOffset | PayloadSize: ${convertBytes(payloadSize)}"
                    )
                }
            }

            when (type) {
                "stsd" -> {
                    parseStsd(boxStart,boxSize)
                    reader.seek(boxStart + boxSize)
                }
                "stts" -> {
                    reader.seek(boxStart + boxSize)
                }
                "stsc" -> {
                    reader.seek(boxStart + boxSize)
                }
                "stsz", "stz2" -> {
                    reader.seek(boxStart + boxSize)
                }
                "stco", "co64" -> {
                    reader.seek(boxStart + boxSize)
                }
                "stss" -> {
                    reader.seek(boxStart + boxSize)
                }
                else -> {
                    reader.seek(boxStart + boxSize)
                }
            }

            reader.seek(boxStart + boxSize)
        }

    }

    fun parseStsd(offset: Long, size: Long): StsdBox {
        reader.seek(offset)
        val end = offset + size

        val boxSize = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes)

        if (type != "stsd") {
            println("Unexpected box type: $type at $offset")
            return StsdBox(offset, size)
        }

        reader.skipBytes(4) // version + flags
        val entryCount = reader.readInt()

        val stsdBox = StsdBox(offset, boxSize)
        var currentOffset = reader.filePointer
        var index = 1

        while (currentOffset + 8 <= end && index <= entryCount) {
            reader.seek(currentOffset)
            val entrySize = reader.readInt().toLong()
            val entryTypeBytes = ByteArray(4).also { reader.readFully(it) }
            val entryType = String(entryTypeBytes)

            val entryEnd = currentOffset + entrySize

            when (entryType) {
                "avc1", "hev1", "hvc1", "vp09", "av01" -> reader.skipBytes(78)
                "mp4a", "ac-3", "ec-3", "opus" -> reader.skipBytes(28)
                else -> reader.skipBytes(8)
            }

            val entry = SampleEntry(entryType, currentOffset, entrySize)
            entry.boxes.addAll(parseSampleEntryBoxes(reader.filePointer, entryEnd, entryType))
            stsdBox.entries.add(entry)

            currentOffset = entryEnd
            index++
        }

        return stsdBox
    }

    fun parseSampleEntryBoxes(start: Long, end: Long, parentType: String): List<Any> {
        val boxes = mutableListOf<Any>()
        var current = start

        while (current + 8 <= end) {
            reader.seek(current)
            val size = reader.readInt().toLong()
            val typeBytes = ByteArray(4).also { reader.readFully(it) }
            val type = String(typeBytes)

            if (size < 8 || current + size > end) break

            val contentOffset = current + 8
            val contentSize = size - 8

            when (type) {
                "avcC" -> boxes.add(parseAvcC(reader,contentOffset))
                "btrt" -> boxes.add(parseBtrt(reader,contentOffset))
                "pasp" -> boxes.add(parsePasp(reader,contentOffset))
                "colr" -> boxes.add(parseColr(reader,contentOffset, contentSize))
                "esds" -> boxes.add(parseEsds(reader,contentOffset))
            }

            current += size
        }
        return boxes
    }

    fun readBoxHeader(): BoxHeader? {
        if (reader.filePointer + 8 > reader.length()) return null

        val startOffset = reader.filePointer
        val size = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes, Charsets.US_ASCII)

        if (size < 8 || startOffset + size > reader.length()) {
            println("⚠️ Invalid box detected: type=$type at $startOffset, size=$size")
            return null
        }

        val payloadOffset = reader.filePointer
        val payloadSize = size - 8

        return BoxHeader(type, size, startOffset, payloadOffset, payloadSize)
    }

    fun skipBox(box: BoxHeader) {
        reader.seek(box.startOffset + box.size)
    }


    fun getSamples(initialChunk: Boolean): List<TrunSampleEntry> {
        val entries = mutableListOf<TrunSampleEntry>()
        val targetSamples = if (initialChunk) 2 else 6
        if (currentMoofBox==null){
            if (moofsList.isEmpty()){
                return entries
            }else{
                val moof=moofsList[0]
                currentMoofBox= MoofParser(reader,moof.boxOffset,moof.boxSize)
                val _entries= currentMoofBox?.getEntries(targetSamples)
                if (_entries!=null){
                    return _entries
                }
            }
        }else{
            val _entries= currentMoofBox!!.getEntries(targetSamples)
            if (_entries.isEmpty()){
                currentMoofBox=null
                moofsList.removeAt(0)
                return getSamples(initialChunk)
            }else{
                return _entries
            }
        }
        return entries
    }
}

