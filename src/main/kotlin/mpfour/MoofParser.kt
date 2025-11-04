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
        reader.seek(offset)
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
            trunEndOffset = size+offset,
            sampleOffset = mdatOffset
        )
    }

    fun getEntries(count: Int):List<TrunSampleEntry>{
        val entries=mutableListOf<TrunSampleEntry>()
        if (trafs.isEmpty()){
            return entries
        }else{
            val trafBox=trafs[0]
            if (trafBox.truns.isEmpty()){
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

        val hasSampleDuration = (trun.flags and 0x000100) != 0
        val hasSampleSize = (trun.flags and 0x000200) != 0
        val hasSampleFlags = (trun.flags and 0x000400) != 0
        val hasSampleCTO = (trun.flags and 0x000800) != 0

        val tfhdFlags = trafBox.tfhdBox.flags
        val hasDefaultDuration = (tfhdFlags and 0x000008) != 0
        val hasDefaultSize = (tfhdFlags and 0x000010) != 0
        val hasDefaultFlags = (tfhdFlags and 0x000020) != 0

        val defaultDuration = if (hasDefaultDuration) trafBox.tfhdBox.defaultSampleDuration else 0
        val defaultSize = if (hasDefaultSize) trafBox.tfhdBox.defaultSampleSize else 0
        val defaultFlags = if (hasDefaultFlags) trafBox.tfhdBox.defaultSampleFlags else 0

        for (i in 0 until trun.totalSampleCount) {
            if (reader.filePointer >= trun.trunEndOffset) break

            val sampleDuration = if (hasSampleDuration) reader.readInt() else defaultDuration
            val sampleSize = if (hasSampleSize) reader.readInt() else defaultSize
            val sampleFlags = if (hasSampleFlags) reader.readInt() else defaultFlags
            val sampleCTO = if (hasSampleCTO) reader.readInt().toLong() else 0L

            val isSync = (sampleFlags?.and(0x00010000)) == 0

            entries.add(
                TrunSampleEntry(
                    frameSize = sampleSize!!,
                    frameAbsOffset = trun.sampleOffset,
                    duration = sampleDuration,
                    flags = sampleFlags,
                    compositionTimeOffset = sampleCTO.toInt(),
                    isSyncSample = isSync
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

}




