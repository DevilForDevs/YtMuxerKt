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
    fun getTrunEntries(trafBox: TrafBox,rsc: Int): MutableList<TrunSampleEntry> {
        val trun = trafBox.truns[0]
        val _entries = mutableListOf<TrunSampleEntry>()
        reader.seek(trun.entriesOffset)
        val hasSampleDuration = (trun.flags and 0x000100) != 0
        val hasSampleSize = (trun.flags and 0x000200) != 0
        val hasSampleFlags = (trun.flags and 0x000400) != 0
        val hasSampleCTO = (trun.flags and 0x000800) != 0

        for (i in 0 until rsc){
            val sampleDuration = if (hasSampleDuration) reader.readInt() else null
            val sampleSize = if (hasSampleSize) reader.readInt() else null
            val sampleFlags = if (hasSampleFlags) reader.readInt() else null
            val sampleCTO = if (hasSampleCTO) {
                if (trun.version == 0) reader.readInt().toLong()
                else reader.readInt().toLong()
            } else null

            val isSync = sampleFlags?.let { (it and 0x00010000) == 0 } ?: true

            _entries.add(
                TrunSampleEntry(
                    frameSize = sampleSize ?: 0,
                    frameAbsOffset = trun.sampleOffset,
                    duration = sampleDuration ?: 0,
                    flags = sampleFlags ?: 0,
                    compositionTimeOffset = sampleCTO?.toInt() ?: 0,
                    isSyncSample = isSync
                )
            )

            if (sampleSize!=null){
                trun.sampleOffset+=sampleSize
            }
            val position=reader.filePointer
            if (trun.trunEndOffset==position){
                trafBox.truns.removeAt(0)
                break
            }else{
                trun.entriesOffset=position

            }
        }
        return _entries
    }






}




