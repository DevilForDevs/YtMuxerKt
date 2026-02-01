package muxer.mpfour

import muxer.mpfour.moofBoxes.TfdtBox
import muxer.mpfour.moofBoxes.TfhdBox
import muxer.mpfour.models.BoxHeader
import muxer.mpfour.models.BrandInfo
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.DinfBox
import muxer.mpfour.models.trakModels.edtsModels.ElstBox
import muxer.mpfour.models.trakModels.edtsModels.ElstEntry
import muxer.mpfour.models.trakModels.mdiaModels.HdlrBox
import muxer.mpfour.models.trakModels.mdiaModels.MdhdBox
import muxer.mpfour.models.Mvhd
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.SmhdBox
import muxer.mpfour.models.trakModels.Tkhd
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.VmhdBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Co64
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Ctts
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sbgp
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sgpd
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stco
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsc
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stss
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsz
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stts
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadUtils(val reader: RandomAccessFile,val doLogging: Boolean){
    fun getBrands(box: BoxHeader): BrandInfo {
        reader.seek(box.payloadOffset)

        // --- Read major brand ---
        val majorBrand = ByteArray(4).also { reader.readFully(it) }
            .toString(Charsets.US_ASCII)

        // --- Read minor version ---
        val minorVersion = reader.readInt()

        // --- Read compatible brands ---
        val compatibleBrands = mutableListOf<String>()
        val end = box.payloadOffset + box.payloadSize

        while (reader.filePointer + 4 <= end) {
            val brandBytes = ByteArray(4)
            val bytesRead = reader.read(brandBytes)
            if (bytesRead < 4) break
            compatibleBrands.add(String(brandBytes, Charsets.US_ASCII))
        }

        return BrandInfo(
            majorBrand = majorBrand,
            minorVersion = minorVersion,
            compatibleBrands = compatibleBrands
        )
    }
     fun readBoxHeader(): BoxHeader? {
        if (reader.filePointer + 8 > reader.length()) return null

        val startOffset = reader.filePointer
        val size = reader.readInt().toLong()
        val typeBytes = ByteArray(4).also { reader.readFully(it) }
        val type = String(typeBytes, Charsets.US_ASCII)

        // --- validate ---
        if (size < 8 || startOffset + size > reader.length()) {
            // Normal EOF guard
            if (size.toInt() == 0 || reader.filePointer >= reader.length() - 8) {
                return null
            }

            // Actual invalid box case
            val stack = Thread.currentThread().stackTrace
            val caller = stack.firstOrNull {
                it.methodName != "readBoxHeader" && it.className.contains("Parser")
            }

            val callerInfo = if (caller != null) {
                "${caller.methodName}() [${caller.fileName}:${caller.lineNumber}]"
            } else {
                "unknown"
            }
            if (size == 1L) {
                if (reader.filePointer + 8 > reader.length()) {
                    println("⚠️ Corrupt extended-size box at $startOffset")
                    return null
                }

                val extSize = reader.readLong()
                if (extSize < 16 || startOffset + extSize > reader.length()) {
                    println("⚠️ Invalid extended-size = $extSize at $startOffset")
                    return null
                }

                return BoxHeader(type, extSize, startOffset, reader.filePointer, extSize - 16)
            }


            println("⚠️ Invalid box detected at $startOffset (size=$size, type=$type) — called from ${callerInfo.replace(")","")}")
            return null
        }


        // --- normal path ---
        val payloadOffset = reader.filePointer
        val payloadSize = size - 8
        return BoxHeader(type, size, startOffset, payloadOffset, payloadSize)
    }



   fun skipBox(box: BoxHeader) {
        reader.seek(box.startOffset + box.size)
    }
   fun boxEnd(boxHeader: BoxHeader): Long {
        return boxHeader.payloadOffset+boxHeader.payloadSize
   }
    fun parseMvhd(boxHeader: BoxHeader): Mvhd {
        // Validate box size
        val boxSizeLong = boxHeader.size
        if (boxSizeLong < 8L) error("mvhd box too small: $boxSizeLong at ${boxHeader.startOffset}")

        // Defensive: prevent huge allocations
        if (boxSizeLong > Int.MAX_VALUE) {
            error("mvhd box too large to read into memory: $boxSizeLong")
        }

        val boxSize = boxSizeLong.toInt()

        // Seek to the beginning of the box (including header)
        reader.seek(boxHeader.startOffset)

        // Read full box bytes (size + type + payload)
        val fullBox = ByteArray(boxSize)
        reader.readFully(fullBox)

        // payload starts at offset 8 (after 4-byte size + 4-byte type)
        val payloadOffset = 8
        val payloadLen = fullBox.size - payloadOffset
        if (payloadLen <= 0) error("No mvhd payload at ${boxHeader.startOffset}")

        val bb = ByteBuffer.wrap(fullBox, payloadOffset, payloadLen).order(ByteOrder.BIG_ENDIAN)

        val version = bb.get().toInt() and 0xFF
        bb.position(bb.position() + 3) // skip flags

        return if (version == 1) {
            val creationTime = bb.long
            val modificationTime = bb.long
            val timeScale = bb.int
            val duration = bb.long

            if (doLogging) {
                println("mvhd v1 timescale=$timeScale duration=$duration creation=$creationTime")
            }

            // Store full box bytes (header + payload). This is the safest for rewriting.
            Mvhd(timeScale, duration, raw = fullBox)
        } else {
            val creationTime = (bb.int.toLong() and 0xFFFFFFFFL)
            val modificationTime = (bb.int.toLong() and 0xFFFFFFFFL)
            val timeScale = bb.int
            val duration = (bb.int.toLong() and 0xFFFFFFFFL)

            if (doLogging) {
                println("mvhd v0 timescale=$timeScale duration=$duration")
            }

            Mvhd(timeScale, duration, raw = fullBox)
        }
    }


    fun convertBytes(sizeInBytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            sizeInBytes >= gigabyte -> String.format("%.2f GB", sizeInBytes.toDouble() / gigabyte)
            sizeInBytes >= megabyte -> String.format("%.2f MB", sizeInBytes.toDouble() / megabyte)
            sizeInBytes >= kilobyte -> String.format("%.2f KB", sizeInBytes.toDouble() / kilobyte)
            else -> "$sizeInBytes Bytes"
        }
    }
    fun parseTkhd(boxHeader: BoxHeader): Tkhd {
        val boxSizeLong = boxHeader.size
        if (boxSizeLong < 8) error("tkhd too small at ${boxHeader.startOffset}")
        if (boxSizeLong > Int.MAX_VALUE) error("tkhd too large")

        val boxSize = boxSizeLong.toInt()

        // Read the entire tkhd box (header + payload)
        reader.seek(boxHeader.startOffset)
        val fullBox = ByteArray(boxSize)
        reader.readFully(fullBox)

        // Parse from inside fullBox (after header)
        val payloadOffset = 8
        val payloadLength = fullBox.size - payloadOffset
        val buffer = ByteBuffer.wrap(fullBox, payloadOffset, payloadLength)
            .order(ByteOrder.BIG_ENDIAN)

        val version = buffer.get().toInt() and 0xFF
        buffer.position(buffer.position() + 3)  // flags

        val creationTime: Long
        val modificationTime: Long
        val trackId: Int
        val duration: Long

        if (version == 1) {
            creationTime = buffer.long
            modificationTime = buffer.long
            trackId = buffer.int
            buffer.int        // skip reserved
            duration = buffer.long
        } else {
            creationTime = buffer.int.toLong() and 0xFFFFFFFFL
            modificationTime = buffer.int.toLong() and 0xFFFFFFFFL
            trackId = buffer.int
            buffer.int        // skip reserved
            duration = buffer.int.toLong() and 0xFFFFFFFFL
        }

        buffer.position(buffer.position() + 8) // reserved

        val layer = buffer.short.toInt()
        val alternateGroup = buffer.short.toInt()
        val volume = buffer.short.toInt() / 256f
        buffer.short    // reserved

        // matrix 9*4
        val matrix = IntArray(9) { buffer.int }

        val width = buffer.int / 65536.0
        val height = buffer.int / 65536.0

        if (doLogging) {
            println("tkhd: trackId=$trackId duration=$duration width=$width height=$height")
        }

        return Tkhd(
            creationTime = creationTime,
            modificationTime = modificationTime,
            trackId = trackId,
            duration = duration,
            widthPixels = width.toInt(),
            heightPixels = height.toInt(),
            layer = layer,
            volume = volume,
            raw = fullBox   // <-- IMPORTANT
        )
    }

    fun parseMdhd(boxHeader: BoxHeader): MdhdBox {
        val sizeLong = boxHeader.size
        if (sizeLong < 8) error("mdhd too small at ${boxHeader.startOffset}")
        if (sizeLong > Int.MAX_VALUE) error("mdhd too large")

        val size = sizeLong.toInt()

        // Read entire mdhd (header + payload)
        reader.seek(boxHeader.startOffset)
        val fullBox = ByteArray(size)
        reader.readFully(fullBox)

        // Parse payload starting after 8 bytes (size + type)
        val payloadOffset = 8
        val bb = ByteBuffer.wrap(fullBox).order(ByteOrder.BIG_ENDIAN)

        bb.position(payloadOffset)

        val version = bb.get().toInt() and 0xFF
        bb.position(bb.position() + 3) // flags

        val creationTime: Long
        val modificationTime: Long
        val timescale: Long
        val duration: Long

        if (version == 1) {
            creationTime = bb.long
            modificationTime = bb.long
            timescale = bb.int.toLong() and 0xFFFFFFFFL
            duration = bb.long
        } else {
            creationTime = bb.int.toLong() and 0xFFFFFFFFL
            modificationTime = bb.int.toLong() and 0xFFFFFFFFL
            timescale = bb.int.toLong() and 0xFFFFFFFFL
            duration = bb.int.toLong() and 0xFFFFFFFFL
        }

        val langBits = bb.short.toInt()
        val language = buildString {
            append(((langBits shr 10) and 0x1F) + 0x60)
            append(((langBits shr 5) and 0x1F) + 0x60)
            append((langBits and 0x1F) + 0x60)
        }

        bb.short  // predefined

        if (doLogging) {
            println(
                "mdhd: timescale=$timescale duration=$duration language=$language"
            )
        }

        return MdhdBox(
            version = version,
            creationTime = creationTime,
            modificationTime = modificationTime,
            timescale = timescale,
            duration = duration,
            language = language,
            raw = fullBox   // <-- IMPORTANT FIX
        )
    }

    // Helper: read full box bytes (size+type+payload)
    fun readFullBox(box: BoxHeader): ByteArray {
        val boxSizeLong = box.size
        require(boxSizeLong >= 8L) { "box too small at ${box.startOffset}" }
        require(boxSizeLong <= Int.MAX_VALUE) { "box too large at ${box.startOffset}" }
        val boxSize = boxSizeLong.toInt()

        val full = ByteArray(boxSize)
        reader.seek(box.startOffset)
        reader.readFully(full)
        return full
    }


    // Updated parseHdlr that stores raw = full box bytes
    fun parseHdlr(box: BoxHeader): HdlrBox {
        val fullBox = readFullBox(box)

        // payload starts after 8 bytes (size + type)
        val payloadOffset = 8
        val payloadLen = fullBox.size - payloadOffset
        val bb = ByteBuffer.wrap(fullBox, payloadOffset, payloadLen).order(ByteOrder.BIG_ENDIAN)

        val version = bb.get().toInt() and 0xFF
        bb.position(bb.position() + 3) // skip flags (3)

        bb.int // pre_defined (usually 0)

        val handlerTypeBytes = ByteArray(4)
        bb.get(handlerTypeBytes)
        val handlerType = String(handlerTypeBytes, Charsets.US_ASCII)

        // reserved[3]
        bb.int; bb.int; bb.int

        val nameLen = payloadLen - bb.position()
        val nameBytes = ByteArray(nameLen.coerceAtLeast(0))
        bb.get(nameBytes)
        val handlerName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')

        if (doLogging) {
            println("Handler Type: $handlerType  Handler Name: $handlerName")
        }

        return HdlrBox(
            handlerType = handlerType,
            handlerName = handlerName,
            raw = fullBox           // <-- store full box (header + payload)
        )
    }

    fun parseElst(offset: Long): ElstBox {
        reader.seek(offset)

        val version = reader.readUnsignedByte()
        val flags = (reader.readUnsignedByte() shl 16) or
                (reader.readUnsignedByte() shl 8) or
                reader.readUnsignedByte()

        val entryCount = reader.readInt()

        val entries = mutableListOf<ElstEntry>()

        repeat(entryCount) {
            val segmentDuration: Long
            val mediaTime: Long

            if (version == 1) {
                // 64-bit values
                segmentDuration = reader.readLong()
                mediaTime = reader.readLong()
            } else {
                // Version 0 = 32-bit signed media_time, unsigned duration
                segmentDuration = reader.readInt().toLong() and 0xFFFFFFFFL

                val rawMediaTime = reader.readInt()
                mediaTime = rawMediaTime.toLong()  // signed 32-bit, correct per spec
            }

            val mediaRateInteger = reader.readShort().toInt()
            val mediaRateFraction = reader.readShort().toInt()

            // media_rate is fixed-point 16.16 value
            val mediaRate = mediaRateInteger + (mediaRateFraction / 65536.0)

            if (doLogging) {
                println(
                    "Segment Duration: $segmentDuration   " +
                            "media rate Int: $mediaRateInteger  " +
                            "Media Fraction: $mediaRateFraction  " +
                            "Media Rate: $mediaRate  " +
                            "Media Time: $mediaTime"
                )
            }

            entries += ElstEntry(
                segmentDuration = segmentDuration,
                mediaTime = mediaTime,
                mediaRate = mediaRate
            )
        }

        return ElstBox(
            version = version,
            flags = flags,
            entries = entries
        )
    }

    fun parseVmhd(offset: Long): VmhdBox {
        reader.seek(offset)
        val version = reader.readUnsignedByte()
        reader.skipBytes(3) // flags (should be 0x000001 for 'vmhd')

        val graphicsMode = reader.readUnsignedShort()
        val opColorR = reader.readUnsignedShort()
        val opColorG = reader.readUnsignedShort()
        val opColorB = reader.readUnsignedShort()
        if (doLogging){
            println("Graphics Mode: $graphicsMode opcolor R: $opColorR  opcolor g: $opColorG  opcolor B: $opColorB")
        }
        return VmhdBox(
            graphicsMode = graphicsMode,
            opColor = Triple(opColorR, opColorG, opColorB)
        )
    }

    fun parseSmhd(offset: Long): SmhdBox {
        reader.seek(offset)
        val version = reader.readUnsignedByte()
        reader.skipBytes(3) // flags

        val balanceFixed16 = reader.readShort().toInt()
        reader.skipBytes(2) // reserved

        val balance = balanceFixed16 / 256f // convert 8.8 fixed-point to float
        if (doLogging){
            println("Balance: $balance")
        }

        return SmhdBox(balance = balance)
    }
    fun parseDinf(boxHeader: BoxHeader): DinfBox {
        val dinfEnd = boxEnd(boxHeader)
        reader.seek(boxHeader.payloadOffset)

        var drefCount = 0

        while (reader.filePointer + 8 <= dinfEnd) {
            val subBox = readBoxHeader() ?: break

            // Boundary validation
            if (subBox.startOffset + subBox.size > dinfEnd) {
                println("⚠ Invalid sub-box in dinf: ${subBox.type} at offset=${subBox.startOffset} size=${subBox.size}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {
                "dref" -> {
                    // Parse dref (Data Reference Box)
                    reader.seek(subBox.payloadOffset)
                    reader.skipBytes(4) // version + flags

                    drefCount = reader.readInt()

                    if (doLogging) {
                        println("→ dref entry count: $drefCount")
                    }

                    // We can skip over the rest of entries for now
                    skipBox(subBox)
                }

                else ->skipBox(subBox)
            }
        }

        return DinfBox(drefCount = drefCount)
    }
    fun parseTfhd(offset: Long,mdatOffset: Long): TfhdBox {
        reader.seek(offset + 8) // skip header ('size' + 'type')
        val fullBoxHeader = ByteArray(4)
        reader.readFully(fullBoxHeader)

        val version = fullBoxHeader[0].toInt() and 0xFF
        val flags = ((fullBoxHeader[1].toInt() and 0xFF) shl 16) or
                ((fullBoxHeader[2].toInt() and 0xFF) shl 8) or
                (fullBoxHeader[3].toInt() and 0xFF)

        val trackId = reader.readInt()
        // Optional fields per ISO/IEC 14496-12 §8.8.7
        var baseDataOffset=mdatOffset
        var sampleDescriptionIndex: Int? = null
        var defaultSampleDuration: Int? = null
        var defaultSampleSize: Int? = null
        var defaultSampleFlags: Int? = null

        if ((flags and 0x000001) != 0) { // base-data-offset-present
            baseDataOffset = reader.readLong()
        }
        if ((flags and 0x000002) != 0) { // sample-description-index-present
            sampleDescriptionIndex = reader.readInt()
        }
        if ((flags and 0x000008) != 0) { // default-sample-duration-present
            defaultSampleDuration = reader.readInt()
        }
        if ((flags and 0x000010) != 0) { // default-sample-size-present
            defaultSampleSize = reader.readInt()
        }
        if ((flags and 0x000020) != 0) { // default-sample-flags-present
            defaultSampleFlags = reader.readInt()
        }
        return TfhdBox(
            version = version,
            flags = flags,
            trackId = trackId,
            baseDataOffset = baseDataOffset,
            sampleDescriptionIndex = sampleDescriptionIndex,
            defaultSampleDuration = defaultSampleDuration,
            defaultSampleSize = defaultSampleSize,
            defaultSampleFlags = defaultSampleFlags
        )
    }
    fun parseTfdt(offset: Long, size: Long): TfdtBox {
        reader.seek(offset + 8) // skip header
        val fullBoxHeader = ByteArray(4)
        reader.readFully(fullBoxHeader)

        val version = fullBoxHeader[0].toInt() and 0xFF
        val flags = ((fullBoxHeader[1].toInt() and 0xFF) shl 16) or
                ((fullBoxHeader[2].toInt() and 0xFF) shl 8) or
                (fullBoxHeader[3].toInt() and 0xFF)

        val baseDecodeTime = when (version) {
            1 -> reader.readLong()
            else -> reader.readInt().toLong()
        }
        if (doLogging){
            println(baseDecodeTime)
        }
        return TfdtBox(
            version = version,
            flags = flags,
            baseMediaDecodeTime = baseDecodeTime,
        )
    }

    fun parseSttsEntries(subBox: BoxHeader): Stts {
        // Read full raw bytes
        val raw = ByteArray(subBox.size.toInt())
        reader.seek(subBox.startOffset)
        reader.readFully(raw)

        // Parse payload
        reader.seek(subBox.payloadOffset)
        reader.skipBytes(4) // version + flags
        val entryCount = reader.readInt()

        if (doLogging) {
            println("stts entries = $entryCount and first 10 Entries")
        }

        // log first 10 entries only
        repeat(entryCount.coerceAtMost(entryCount)) { i ->
            val sampleCount = reader.readInt()
            val sampleDelta = reader.readInt()
            if (doLogging) println("[$i] sampleCount=$sampleCount, sampleDelta=$sampleDelta")
        }

        // skip remaining entries
        val remaining = entryCount - 10
        if (remaining > 0) reader.skipBytes(remaining * 8)

        return Stts(
            boxOffset = subBox.startOffset,
            boxSize   = subBox.size,
            raw       = raw
        )
    }

    fun parseCttsEntries(subBox: BoxHeader): Ctts {
        // Read full raw bytes
        val raw = ByteArray(subBox.size.toInt())
        reader.seek(subBox.startOffset)
        reader.readFully(raw)

        // Parse payload
        reader.seek(subBox.payloadOffset)
        reader.skipBytes(4) // version + flags
        val entryCount = reader.readInt()

        if (doLogging) {
            println("ctts entries = $entryCount and first 10 Entries")
        }

        repeat(entryCount.coerceAtMost(10)) { i ->
            val sampleCount = reader.readInt()
            val sampleOffset = reader.readInt()
            if (doLogging) println("[$i] sampleCount=$sampleCount, sampleOffset=$sampleOffset")
        }

        val remaining = entryCount - 10
        if (remaining > 0) reader.skipBytes(remaining * 8)

        return Ctts(
            boxOffset = subBox.startOffset,
            boxSize   = subBox.size,
            raw = raw
        )
    }


    private fun fourCCFromInt(v: Int): String {
        val b0 = ((v ushr 24) and 0xFF).toByte()
        val b1 = ((v ushr 16) and 0xFF).toByte()
        val b2 = ((v ushr 8) and 0xFF).toByte()
        val b3 = (v and 0xFF).toByte()
        return String(byteArrayOf(b0, b1, b2, b3), Charsets.US_ASCII)
    }

    fun parseSbgpEntries(subBox: BoxHeader): Sbgp {
        reader.seek(subBox.payloadOffset)

        val version = reader.readByte().toInt() and 0xFF
        reader.skipBytes(3) // flags

        val groupingTypeInt = reader.readInt()
        val groupingType = fourCCFromInt(groupingTypeInt)

        val groupingTypeParam =
            if (version == 1) reader.readInt() else null

        val entryCount = reader.readInt()
        if (doLogging) {
            println("sbgp: groupingType=$groupingType groupingTypeParam=$groupingTypeParam entryCount=$entryCount")
        }

        var printed = 0
        repeat(entryCount) { i ->
            val sampleCount = reader.readInt()
            val groupDescriptionIndex = reader.readInt()

            if (doLogging && printed < 10) {
                println(" [$i] sampleCount=$sampleCount  groupDescIdx=$groupDescriptionIndex")
                printed++
            }
        }



        return Sbgp(subBox.startOffset, subBox.size)
    }

    fun parseSgpdEntries(subBox: BoxHeader): Sgpd {
        reader.seek(subBox.payloadOffset)

        val version = reader.readByte().toInt() and 0xFF
        reader.skipBytes(3) // flags

        val groupingTypeInt = reader.readInt()
        val groupingType = fourCCFromInt(groupingTypeInt)

        // default_length present only in version 1 (ISO/IEC 14496-12)
        val defaultLength =
            if (version == 1) reader.readInt() else 0

        val entryCount = reader.readInt()
        if (doLogging) {
            println("sgpd: groupingType=$groupingType entryCount=$entryCount defaultLength=$defaultLength")
        }

        var printed = 0
        repeat(entryCount) { i ->
            val descLength = if (defaultLength == 0) reader.readInt() else defaultLength
            if (doLogging && printed < 10) {
                println(" [$i] descLength=$descLength (raw group data skipped)")
                printed++
            }
            // guard against bad lengths
            if (descLength > 0) reader.skipBytes(descLength)
        }



        return Sgpd(subBox.startOffset, subBox.size)
    }


    fun parseStscEntries(subBox: BoxHeader): Stsc {
        // Read full box raw bytes (header + payload)
        val raw = ByteArray(subBox.size.toInt())
        reader.seek(subBox.startOffset)
        reader.readFully(raw)

        // Now parse payload
        reader.seek(subBox.payloadOffset)
        reader.skipBytes(4) // version + flags

        val entryCount = reader.readInt()
        if (doLogging) {
            println("stsc entries = $entryCount and first 10 Entries")
        }

        repeat(minOf(entryCount, 10)) { i ->
            val firstChunk = reader.readInt()
            val samplesPerChunk = reader.readInt()
            val sampleDescIndex = reader.readInt()

            if (doLogging) {
                println("  [$i] firstChunk=$firstChunk, samplesPerChunk=$samplesPerChunk, descIndex=$sampleDescIndex")
            }
        }

        // Skip remaining entries after first 10
        val remaining = entryCount - 10
        if (remaining > 0) {
            reader.skipBytes(remaining * 12)
        }

        return Stsc(
            boxOffset = subBox.startOffset,
            boxSize = subBox.size,
            raw = raw
        )
    }


    fun parseStszEntries(subBox: BoxHeader): Stsz {
        // --- Capture raw bytes first (ALWAYS do raw first!) ---
        reader.seek(subBox.startOffset)
        val raw = ByteArray(subBox.size.toInt())
        reader.readFully(raw)

        // --- Now parse using a temp reader on raw ---
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)

        buf.position(8)               // skip size + type
        buf.int                       // version + flags
        val sampleSize = buf.int
        val sampleCount = buf.int

        if (doLogging) {
            println("stsz sampleSize=$sampleSize, entries=$sampleCount and first 10 entries")
        }

        if (sampleSize == 0) {
            // variable per-sample sizes
            for (i in 0 until sampleCount) {
                val size = buf.int
                if (doLogging && i < sampleCount) {
                    println("  [$i] sampleSize=$size")
                }
            }
        }

        return Stsz(
            boxOffset = subBox.startOffset,
            boxSize = subBox.size,
            raw = raw
        )
    }


    fun parseStssEntries(subBox: BoxHeader): Stss {
        // Read full box raw bytes (header + payload)
        val raw = ByteArray(subBox.size.toInt())
        reader.seek(subBox.startOffset)
        reader.readFully(raw)

        // Now parse the payload
        reader.seek(subBox.payloadOffset)
        reader.skipBytes(4) // version + flags

        val entryCount = reader.readInt()
        if (doLogging) {
            println("stss keyframes entry count =$entryCount and first 10 entries")
        }

        repeat(minOf(entryCount, 10)) { i ->
            val sampleNumber = reader.readInt()
            if (doLogging) println("  [$i] sample=$sampleNumber")
        }

        val remaining = entryCount - 10
        if (remaining > 0) reader.skipBytes(remaining * 4)

        return Stss(
            boxOffset = subBox.startOffset,
            boxSize = subBox.size,
            raw = raw
        )
    }
    fun parseCo64Entries(subBox: BoxHeader): Co64 {
        // --- FIRST: Capture raw bytes ---
        reader.seek(subBox.startOffset)
        val raw = ByteArray(subBox.size.toInt())
        reader.readFully(raw)

        // --- Parse using a separate ByteBuffer ---
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)

        buf.position(8)                // skip size + type
        buf.int                        // version + flags
        val entryCount = buf.int

        if (doLogging) {
            println("co64 chunkOffsets entries count=$entryCount and first 10 Entries")
        }

        for (i in 0 until entryCount) {
            val offset = buf.long  // <-- 64-bit offset
            if (doLogging && i < 10) {
                println("  [$i] chunkOffset=$offset")
            }
        }

        return Co64(
            boxOffset = subBox.startOffset,
            boxSize = subBox.size,
            raw = raw
        )
    }



    fun parseStcoEntries(subBox: BoxHeader): Stco {
        // --- FIRST: Capture raw bytes ---
        reader.seek(subBox.startOffset)
        val raw = ByteArray(subBox.size.toInt())
        reader.readFully(raw)

        // --- Parse using a separate ByteBuffer ---
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)

        buf.position(8)                // skip size + type
        buf.int                        // version + flags
        val entryCount = buf.int

        if (doLogging) {
            println("stco chunkOffsets entries count=$entryCount and first 10 Entries")
        }

        for (i in 0 until entryCount) {
            val offset = buf.int
            if (doLogging && i < 10) {
                println("  [$i] chunkOffset=$offset")
            }
        }

        return Stco(
            boxOffset = subBox.startOffset,
            boxSize = subBox.size,
            raw = raw
        )
    }


}

/*ffprobe -hide_banner -show_format -show_streams kyaoutpt.mp4
*/
