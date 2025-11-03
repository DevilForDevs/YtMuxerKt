package mpfour

import mpfour.models.TfdtBox
import mpfour.models.TfhdBox
import mpfour.models.TrafBox
import mpfour.models.TrunBox
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder



class MoofParser(
    private val reader: RandomAccessFile,
    private val offset: Long,
    private val size: Long
) {
    private val trafs: List<TrafBox>
    private val mdatOffset: Long = offset + size + 8

    // Cursor state — safe to cache across calls
    private var trafIndex = 0
    private var trunIndex = 0
    private var sampleInTrun = 0

    init {
        reader.seek(offset)
        trafs = parseTrafStructure()
    }

    private fun parseTrafStructure(): List<TrafBox> {
        val result = mutableListOf<TrafBox>()
        var innerOffset = offset
        val end = offset + size

        while (innerOffset + 8 <= end) {
            reader.seek(innerOffset)
            val header = ByteArray(8)
            reader.readFully(header)

            val boxSize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
            val type = String(header, 4, 4, Charsets.US_ASCII)

            if (boxSize <= 0 || innerOffset + boxSize > end) break

            when (type) {
                "traf" -> {
                    val traf = parseTraf(innerOffset + 8, boxSize - 8)
                    if (traf != null) result.add(traf)
                }
            }
            innerOffset += boxSize
        }
        return result
    }

    private fun parseTraf(trafOffset: Long, trafSize: Long): TrafBox? {
        var innerOffset = trafOffset
        val end = trafOffset + trafSize

        var tfhdBox: TfhdBox? = null
        var tfdtBox: TfdtBox? = null
        val truns = mutableListOf<TrunBox>()

        while (innerOffset + 8 <= end) {
            reader.seek(innerOffset)
            val header = ByteArray(8)
            reader.readFully(header)

            val boxSize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
            val type = String(header, 4, 4, Charsets.US_ASCII)

            if (boxSize <= 0 || innerOffset + boxSize > end) break

            when (type) {
                "tfhd" -> tfhdBox = parseTfhd(innerOffset + 8, boxSize - 8)
                "tfdt" -> tfdtBox = parseTfdt(innerOffset + 8, boxSize - 8)
                "trun" -> {
                    val trun = parseTrun(innerOffset, boxSize)
                    if (trun != null) truns.add(trun)
                }
            }
            innerOffset += boxSize
        }

        return if (tfhdBox != null && tfdtBox != null) {
            TrafBox(truns, tfhdBox, tfdtBox)
        } else null
    }

    private fun parseTfhd(offset: Long, size: Long): TfhdBox {
        reader.seek(offset)
        val flags = (reader.readUInt8() shl 16) or (reader.readUInt8() shl 8) or reader.readUInt8()
        val trackId = reader.readInt()

        var baseDataOffset: Long? = null
        var sampleDescriptionIndex: Int? = null

        if (flags and 0x000001 != 0) baseDataOffset = reader.readLong()
        if (flags and 0x000002 != 0) sampleDescriptionIndex = reader.readInt()

        return TfhdBox(trackId, baseDataOffset?.toInt() ?: -1, sampleDescriptionIndex?.toInt() ?: -1)
    }

    private fun parseTfdt(offset: Long, size: Long): TfdtBox {
        reader.seek(offset)
        val version = reader.readUInt8()
        reader.skipBytes(3) // flags
        val time = if (version == 1) reader.readLong() else reader.readInt().toLong()
        return TfdtBox(
            flags = -1,
            baseMediaDecodeTime = time,
            version = 1
        )
    }

    private fun parseTrun(boxOffset: Long, boxSize: Long): TrunBox? {
        reader.seek(boxOffset + 8)

        val version = reader.readUInt8()
        val flags = (reader.readUInt8() shl 16) or (reader.readUInt8() shl 8) or reader.readUInt8()
        val sampleCount = reader.readUInt32().toInt()

        var dataOffset: Int? = null
        var firstSampleFlags: Int? = null

        if (flags and 0x000001 != 0) dataOffset = reader.readInt()
        if (flags and 0x000004 != 0) firstSampleFlags = reader.readInt()

        val entriesStart = reader.filePointer
        val hasSize = (flags and 0x000200) != 0
        val perSampleBytes = (if (flags and 0x000100 != 0) 4 else 0) +
                (if (hasSize) 4 else 0) +
                (if (flags and 0x000400 != 0) 4 else 0) +
                (if (flags and 0x000800 != 0) 4 else 0)

        var sampleSizes: IntArray? = null
        var cumulativeSizes: LongArray? = null

        if (hasSize && sampleCount > 0) {
            val sizes = IntArray(sampleCount)
            var pos = entriesStart

            reader.seek(pos)
            for (i in 0 until sampleCount) {
                if (flags and 0x000100 != 0) reader.skipBytes(4)
                sizes[i] = reader.readInt()
                if (flags and 0x000400 != 0) reader.skipBytes(4)
                if (flags and 0x000800 != 0) reader.skipBytes(4)
            }

            val cum = LongArray(sampleCount + 1)
            var sum = 0L
            for (i in 0 until sampleCount) {
                cum[i + 1] = cum[i] + sizes[i]
            }
            sampleSizes = sizes
            cumulativeSizes = cum
        }

        return TrunBox(
            version = version,
            flags = flags,
            totalSampleCount = sampleCount,
            dataOffset = dataOffset,
            firstSampleFlags = firstSampleFlags,
            entriesOffset = entriesStart,
            sampleOffsetBase = mdatOffset,
            sampleSizes = sampleSizes,
            cumulativeSizes = cumulativeSizes
        )
    }

    fun getEntries(requiredSamples: Int): List<TrunSampleEntry> {
        val entries = mutableListOf<TrunSampleEntry>()
        var remaining = requiredSamples

        while (remaining > 0 && trafIndex < trafs.size) {
            val traf = trafs[trafIndex]
            if (trunIndex >= traf.truns.size) {
                trafIndex++
                trunIndex = 0
                sampleInTrun = 0
                continue
            }

            val trun = traf.truns[trunIndex]
            val samplesLeft = trun.totalSampleCount - sampleInTrun
            if (samplesLeft <= 0) {
                trunIndex++
                sampleInTrun = 0
                continue
            }

            val take = minOf(remaining, samplesLeft)
            val parsed = readTrunSamples(trun, sampleInTrun, take)
            entries.addAll(parsed)

            sampleInTrun += take
            remaining -= take

            if (sampleInTrun >= trun.totalSampleCount) {
                trunIndex++
                sampleInTrun = 0
            }
        }

        return entries
    }

    private fun readTrunSamples(trun: TrunBox, startSample: Int, count: Int): List<TrunSampleEntry> {
        val entries = mutableListOf<TrunSampleEntry>()

        val hasDuration = (trun.flags and 0x000100) != 0
        val hasSize = (trun.flags and 0x000200) != 0
        val hasFlags = (trun.flags and 0x000400) != 0
        val hasCTO = (trun.flags and 0x000800) != 0

        val perSampleBytes = (if (hasDuration) 4 else 0) +
                (if (hasSize) 4 else 0) +
                (if (hasFlags) 4 else 0) +
                (if (hasCTO) 4 else 0)

        val byteOffset = trun.entriesOffset + (startSample * perSampleBytes).toLong()
        reader.seek(byteOffset)

        var sampleStart = trun.sampleOffsetBase
        if (startSample > 0 && trun.cumulativeSizes != null) {
            sampleStart += trun.cumulativeSizes[startSample]
        }

        repeat(count) { i ->
            val duration = if (hasDuration) reader.readInt() else 0
            val size = if (hasSize) reader.readInt() else trun.sampleSizes?.get(startSample + i) ?: 0
            val flags = if (hasFlags) reader.readInt() else 0
            val cto = if (hasCTO) reader.readInt().toLong() else 0L

            val isSync = flags and 0x00010000 == 0

            entries.add(TrunSampleEntry(
                frameSize = size,
                frameAbsOffset = sampleStart,
                duration = duration,
                flags = flags,
                compositionTimeOffset = cto.toInt(),
                isSyncSample = isSync
            ))

            sampleStart += size
        }

        return entries
    }

    fun reset() {
        trafIndex = 0
        trunIndex = 0
        sampleInTrun = 0
    }
}




