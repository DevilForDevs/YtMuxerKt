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

    val mdatOffset=offset+size+8
    val trafs=mutableListOf<TrafBox>()


    init {
        parseTrafStructure()
    }
    private fun parseTrafStructure(){
        var innerOffset = offset
        val trafEnd = offset + size
        while (innerOffset + 8 <= trafEnd) {
            reader.seek(innerOffset)
            val header = ByteArray(8)
            reader.readFully(header)

            val size = ByteBuffer.wrap(header, 0, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int.toLong()
            val type = String(header, 4, 4, Charsets.US_ASCII)

            // safety: if size=0 or invalid, stop
            if (size <= 0 || innerOffset + size > trafEnd) break


            when (type) {
                "mfhd" -> {
                    //it contains sequence/index/postiotn of this moof (optional)
                }
                "traf" -> {
                    trafParser(innerOffset + 8, size - 8)
                }
                else -> {
                    // Unhandled sub-box; skip it
                }
            }

            innerOffset += size
        }
    }
    private fun trafParser(trafOffset: Long, trafSize: Long) {
        var innerOffset = trafOffset
        val trafEnd = trafOffset + trafSize

        var tfhdBox: TfhdBox?=null
        var tfdt: TfdtBox?=null
        val truns=mutableListOf<TrunBox>()

        while (innerOffset + 8 <= trafEnd) {
            reader.seek(innerOffset)
            val header = ByteArray(8)
            reader.readFully(header)

            val size = ByteBuffer.wrap(header, 0, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int.toLong()
            val type = String(header, 4, 4, Charsets.US_ASCII)

            if (size <= 0 || innerOffset + size > trafEnd) break

            when (type) {
                "tfhd" -> tfhdBox = parseTfhd(reader,innerOffset,100L)
                "tfdt" -> tfdt = parseTfdt(reader,innerOffset, size)
                "trun" ->{
                    val trun=parseTrun(innerOffset, size)
                    truns.add(trun)
                }
                else   ->{
                    //unknown box
                }
            }

            innerOffset += size
        }
        trafs.add(
            TrafBox(
                truns = truns,
                tfhdBox = tfhdBox!!,
                tfdtBox = tfdt!!
            )
        )
    }
    private fun parseTrun(offset: Long, size: Long): TrunBox {
        // offset is start of box (size + type). Skip header (8 bytes).
        reader.seek(offset + 8)

        val version = reader.readUInt8()
        val flags = (reader.readUInt8() shl 16) or
                (reader.readUInt8() shl 8) or
                reader.readUInt8()

        // sample_count is uint32
        val sampleCount = reader.readUInt32().toInt() // keep as Int for iteration, but validate below

        var dataOffset: Int? = null
        var firstSampleFlags: Int? = null

        if (flags and 0x000001 != 0) dataOffset = reader.readInt()
        if (flags and 0x000004 != 0) firstSampleFlags = reader.readInt()

        val entriesStart = reader.filePointer
        // entriesLength is the payload left for entries inside this trun box
        val entriesLength = size - (entriesStart - offset)

        return TrunBox(
            version = version,
            flags = flags,
            totalSampleCount = sampleCount,
            dataOffset = dataOffset,
            firstSampleFlags = firstSampleFlags,
            entriesOffset = entriesStart,
            trunEndOffset = entriesLength
        )
    }

    fun getEntries() {
        val traf = trafs[0]
        val trun = traf.truns[0]

        // sanity: ensure entriesOffset is set
        reader.seek(trun.entriesOffset)

        val hasSampleDuration = (trun.flags and 0x000100) != 0
        val hasSampleSize = (trun.flags and 0x000200) != 0
        val hasSampleFlags = (trun.flags and 0x000400) != 0
        val hasSampleCompositionTimeOffset = (trun.flags and 0x000800) != 0

        // compute per-sample size from flags
        var perSampleBytes = 0
        if (hasSampleDuration) perSampleBytes += 4
        if (hasSampleSize) perSampleBytes += 4
        if (hasSampleFlags) perSampleBytes += 4
        if (hasSampleCompositionTimeOffset) perSampleBytes += 4

        // guard: avoid insane sample counts
        val maxPossibleSamples = if (perSampleBytes > 0) (trun.trunEndOffset / perSampleBytes).toInt() else Int.MAX_VALUE
        val sampleCount = trun.totalSampleCount.coerceAtMost(maxPossibleSamples)

        println("---- Parsing trun entries ----")
        println("Version: ${trun.version}, Flags: ${String.format("0x%06X", trun.flags)}")
        println("Sample count (declared): ${trun.totalSampleCount}, (capped to) $sampleCount")
        println("Data offset: ${trun.dataOffset ?: "none"}")
        println("First sample flags: ${trun.firstSampleFlags?.let { String.format("0x%08X", it) } ?: "none"}")
        println("--------------------------------")

        for (i in 0 until sampleCount) {
            val sampleDuration = if (hasSampleDuration) reader.readInt() else null
            val sampleSize = if (hasSampleSize) reader.readInt() else null
            val sampleFlags = if (hasSampleFlags) reader.readInt() else null
            val sampleCTO = if (hasSampleCompositionTimeOffset) {
                if (trun.version == 0) reader.readInt()
                else reader.readInt().toLong() // version 1: signed 32-bit
            } else null

            println("Entry #$i:")
            println("  Duration = ${sampleDuration ?: "-"}")
            println("  Size     = ${sampleSize ?: "-"}")
            println("  Flags    = ${sampleFlags?.let { String.format("0x%08X", it) } ?: "-"}")
            println("  CTO      = ${sampleCTO ?: "-"}")
            println("-------------------------------")
        }

        // If declared count > capped count, warn
        if (trun.totalSampleCount > sampleCount) {
            println("Warning: declared sample_count (${trun.totalSampleCount}) exceeds available data; parsing capped at $sampleCount.")
        } else {
            println("Finished reading $sampleCount trun entries ✅")
        }
    }

}




