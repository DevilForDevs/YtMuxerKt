package muxer.mpfour

import muxer.mpfour.moofBoxes.TfdtBox
import muxer.mpfour.moofBoxes.TfhdBox
import muxer.mpfour.moofBoxes.TrafBox
import muxer.mpfour.moofBoxes.TrunBox
import muxer.mpfour.models.BoxHeader
import muxer.mpfour.models.TrunSampleEntry
import java.io.RandomAccessFile


class MoofParser(
    private val reader: RandomAccessFile,
    val offset: Long,
    val size: Long,
    doLogging: Boolean
){

    val mdatOffset=offset+size+8
    val trafs=mutableListOf<TrafBox>()
    val utils= ReadUtils(reader,doLogging)

    init {
        reader.seek(offset)
        parseTrafStructure()
    }
    private fun parseTrafStructure() {
        var innerOffset = offset
        val trafEnd = offset + size

        while (innerOffset + 8 <= trafEnd) {
            reader.seek(innerOffset)
            val boxHeader = readBoxHeader() ?: break

            if (boxHeader.startOffset + boxHeader.size > trafEnd || boxHeader.size < 8) {
                println("⚠ Invalid box in traf structure: ${boxHeader.type} at ${boxHeader.startOffset}")
                break
            }

            when (boxHeader.type) {
                "mfhd" -> {
                    // optional movie fragment header
                    skipBox(boxHeader)
                }
                "traf" -> {
                    trafParser(boxHeader)
                }
                else -> skipBox(boxHeader)
            }

            innerOffset = boxHeader.startOffset + boxHeader.size
        }
    }

    private fun trafParser(trafHeader: BoxHeader) {
        val trafOffset = trafHeader.payloadOffset
        val trafEnd = trafHeader.payloadOffset + trafHeader.payloadSize

        var tfhdBox: TfhdBox? = null
        var tfdtBox: TfdtBox? = null
        val truns = mutableListOf<TrunBox>()

        while (reader.filePointer + 8 <= trafEnd) {
            val subBox = readBoxHeader() ?: break

            if (subBox.startOffset + subBox.size > trafEnd) {
                println("⚠ Invalid sub-box in traf: ${subBox.type}")
                break
            }

            when (subBox.type) {
                "tfhd" -> {
                    tfhdBox = utils.parseTfhd(subBox.payloadOffset, subBox.payloadSize)
                    skipBox(subBox)
                }
                "tfdt" -> {
                    tfdtBox = utils.parseTfdt(subBox.payloadOffset, subBox.payloadSize)
                    skipBox(subBox)
                }
                "trun" -> {
                    val trun = parseTrun(subBox)
                    truns.add(trun)
                    skipBox(subBox)
                }
                else -> skipBox(subBox)
            }
        }

        // Only add if valid
        if (tfhdBox != null && tfdtBox != null) {
            trafs.add(
                TrafBox(
                    truns = truns,
                    tfhdBox = tfhdBox,
                    tfdtBox = tfdtBox
                )
            )
        }
    }

    private fun parseTrun(boxHeader: BoxHeader): TrunBox {
        reader.seek(boxHeader.payloadOffset)

        val version = reader.readUInt8()
        val flags = (reader.readUInt8() shl 16) or
                (reader.readUInt8() shl 8) or
                reader.readUInt8()

        val sampleCount = reader.readUInt32().toInt()
        var dataOffset: Int? = null
        var firstSampleFlags: Int? = null

        if (flags and 0x000001 != 0) dataOffset = reader.readInt()
        if (flags and 0x000004 != 0) firstSampleFlags = reader.readInt()

        val entriesStart = reader.filePointer
        val entriesLength = boxHeader.size - (entriesStart - boxHeader.startOffset)

        return TrunBox(
            version = version,
            flags = flags,
            totalSampleCount = sampleCount,
            dataOffset = dataOffset,
            firstSampleFlags = firstSampleFlags,
            entriesOffset = entriesStart,
            trunEndOffset = boxHeader.startOffset + boxHeader.size,
            sampleOffset = mdatOffset
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
            // ⚠ Only build stack trace on error
            val stack = Thread.currentThread().stackTrace
            val caller = stack.firstOrNull {
                it.methodName != "readBoxHeader" && it.className.contains("Parser")
            }

            val callerInfo = if (caller != null) {
                "${caller.methodName}() [${caller.fileName}:${caller.lineNumber}]"
            } else {
                "unknown"
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

    fun getEntries(count: Int):List<TrunSampleEntry>{
        val entries=mutableListOf<TrunSampleEntry>()
        if (trafs.isEmpty()){
            println("trafs is empty")
            return entries
        }else{
            val trafBox=trafs[0]
            if (trafBox.truns.isEmpty()){
                trafs.removeAt(0)
                return entries
            }else{
                return getTrunEntries(trafBox,count)
            }

        }
    }

    fun getTrunEntries(trafBox: TrafBox, rsc: Int): MutableList<TrunSampleEntry> {
        val trun = trafBox.truns.firstOrNull() ?: return mutableListOf()
        val entries = mutableListOf<TrunSampleEntry>()

        reader.seek(trun.entriesOffset)

        // trun flags
        val hasSampleDuration = (trun.flags and 0x000100) != 0
        val hasSampleSize     = (trun.flags and 0x000200) != 0
        val hasSampleFlags    = (trun.flags and 0x000400) != 0
        val hasSampleCTO      = (trun.flags and 0x000800) != 0

        // tfhd flags
        val tfhdFlags = trafBox.tfhdBox.flags
        val hasDefaultDuration = (tfhdFlags and 0x000008) != 0
        val hasDefaultSize     = (tfhdFlags and 0x000010) != 0
        val hasDefaultFlags    = (tfhdFlags and 0x000020) != 0

        val defaultDuration = if (hasDefaultDuration) trafBox.tfhdBox.defaultSampleDuration else 0
        val defaultSize     = if (hasDefaultSize)     trafBox.tfhdBox.defaultSampleSize     else 0
        val defaultFlags    = if (hasDefaultFlags)    trafBox.tfhdBox.defaultSampleFlags    else 0

        // Helper: correct keyframe detection
        fun isKeyframe(sampleFlagsInt: Int?, fallbackDefault: Int?): Boolean {
            val flags = sampleFlagsInt ?: fallbackDefault ?: 0
            // Bit 16 (0x00010000) = is_non_sync_sample
            // CLEAR (0) = keyframe, SET (1) = non-keyframe
            return (flags and 0x00010000) == 0
        }

        for (i in 0 until rsc) {
            if (reader.filePointer >= trun.trunEndOffset) break

            val sampleDuration = if (hasSampleDuration) reader.readInt() else defaultDuration
            val sampleSize     = if (hasSampleSize)     reader.readInt() else defaultSize
            val sampleFlags    = if (hasSampleFlags)    reader.readInt() else defaultFlags
            val sampleCTO      = if (hasSampleCTO)      reader.readInt().toLong() else 0L

            // FIXED: Correct parameter order and values
            val isSyncSample = isKeyframe(
                sampleFlagsInt = if (hasSampleFlags) sampleFlags else null,
                fallbackDefault = if (hasDefaultFlags) defaultFlags else null
            )

            entries.add(
                TrunSampleEntry(
                    frameSize             = sampleSize!!,
                    frameAbsOffset        = trun.sampleOffset,
                    duration              = sampleDuration,
                    flags                 = sampleFlags,
                    compositionTimeOffset = sampleCTO.toInt(),
                    isSyncSample          = isSyncSample  // ← Now correct!
                )
            )

            trun.sampleOffset += sampleSize
            val pos = reader.filePointer

            if (pos >= trun.trunEndOffset) {
                trafBox.truns.removeAt(0)
                break
            } else {
                trun.entriesOffset = pos
            }
        }

        return entries
    }

    fun RandomAccessFile.readUInt8(): Int =
        readUnsignedByte()

    fun RandomAccessFile.readUInt32(): Long {
        val b1 = this.readUnsignedByte().toLong()
        val b2 = this.readUnsignedByte().toLong()
        val b3 = this.readUnsignedByte().toLong()
        val b4 = this.readUnsignedByte().toLong()
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }


}




