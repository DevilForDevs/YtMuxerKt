package muxer.mpfour

import muxer.mpfour.models.ChunkPlan
import muxer.mpfour.models.MoovBox
import muxer.mpfour.models.TrunSampleEntry
import muxer.mpfour.models.trakModels.MdiaBox
import muxer.mpfour.models.trakModels.TrakBox
import muxer.mpfour.models.trakModels.mdiaModels.HdlrBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.StblBox
import java.io.RandomAccessFile
import java.nio.ByteBuffer


class DashedWriter(
    private val output: RandomAccessFile,
    private val startOffset: Long,
    private val sources: MutableList<DashedParser>,
    private val sampleWritten:() -> Unit = { }
) {
    private val utils = WriteUtils()
    private var hereSamplesBegins=-1L
    var stssIndex = 0
    val chunk = mutableListOf<TrunSampleEntry>()
    var durationMuxed=0
    var nextPrintMark = 7680 // 0.5 sec

    fun buildNonFmp4() {
        val source = sources[0]
        output.seek(startOffset)

        // === Write ftyp ===
        val ftyp = utils.writeFtyp(source.brandInfo)
        output.write(ftyp)
        // === Write moov ===
        writeMoov(source.moovBox!!)
        writeMdat()

        //close the sources and output
        output.close()
        for (source in sources){
            source.reader.close()
        }

    }

    private fun writeMoov(moov: MoovBox) {
        val moovStart = output.filePointer
        output.writeInt(0)
        output.write("moov".toByteArray())

        // ------------ Determine VIDEO timescale (canonical) ------------
        val videoSource = sources.first { it.hdlrBox!!.handlerType == "vide" }
        val videoTimeScale = videoSource.moovBox!!.mvhdBox.timeScale

        // ------------ Convert all durations to VIDEO timescale ------------
        val maxDuration = sources.maxOf { source ->
            val srcMvhd = source.moovBox!!.mvhdBox
            (srcMvhd.duration.toDouble() * videoTimeScale / srcMvhd.timeScale).toLong()
        }

        // ------------ Write mvhd (scaled) ---------------------
        val mvhd = utils.writeMvhd(
            moov.mvhdBox,
            videoTimeScale,
            maxDuration.toInt()
        )
        output.write(mvhd)

        // ------------ Write each trak -------------------------
        for (source in sources) {
            writeTrak(source.moovBox!!.trakBoxes[0])
        }

        // ------------ Fix moov size ----------------------------
        val moovEnd = output.filePointer
        val moovSize = (moovEnd - moovStart).toInt()
        output.seek(moovStart)
        output.writeInt(moovSize)
        output.seek(moovEnd)
    }

    private fun writeTrak(trak: TrakBox) {

        val trakStart = output.filePointer
        output.writeInt(0)
        output.write("trak".toByteArray(Charsets.US_ASCII))
        output.write(utils.writeTkhd(
            trak.tkhdBox.duration,
            width = trak.tkhdBox.widthPixels,
            height = trak.tkhdBox.heightPixels
        ))
        if (trak.edtsBox!=null){
            output.write(utils.buildEdtsBox(
                segmentDuration = trak.edtsBox.elstBox!!.entries[0].segmentDuration.toInt(),
                skip = trak.edtsBox.elstBox.entries[0].mediaTime.toInt()
            ))
        }

        writeMdia(trak.mdiaBox)

        val trakEnd = output.filePointer
        val trakSize = (trakEnd - trakStart).toInt()
        output.seek(trakStart)
        output.writeInt(trakSize)
        output.seek(trakEnd)
    }
    private fun writeMdia(mdia: MdiaBox) {
        val mdiaStart = output.filePointer
        output.writeInt(0)
        output.write("mdia".toByteArray(Charsets.US_ASCII))

        output.write(utils.writeMdhd(mdia.mdhdBox,null,null))
        output.write(utils.writeHdlr(mdia.hdlrBox))
        writeMinf(mdia)


        val mdiaEnd = output.filePointer
        val mdiaSize = (mdiaEnd - mdiaStart).toInt()

        output.seek(mdiaStart)
        output.writeInt(mdiaSize)
        output.seek(mdiaEnd)
    }
    private fun writeMinf(mdia: MdiaBox) {
        val minfStart = output.filePointer
        output.writeInt(0)
        output.write("minf".toByteArray(Charsets.US_ASCII))
        if (mdia.minfBox?.mediaHandler != null) {
            output.write(mdia.minfBox.mediaHandler)
        } else {
            utils.writeEmptyHandler(mdia.hdlrBox.handlerType, output)
        }

        val drefBox = ByteBuffer.allocate(28).apply {
            putInt(28); put("dref".toByteArray())
            putInt(0); putInt(1)
            putInt(12); put("url ".toByteArray()); putInt(1)
        }.array()

        val dinfBox = ByteBuffer.allocate(36).apply {
            putInt(36); put("dinf".toByteArray())
            put(drefBox)
        }.array()

        output.write(dinfBox)

        val stblStart = output.filePointer
        writeStbl(mdia.minfBox!!.stblBox,mdia.hdlrBox)


        val minfEnd = output.filePointer
        val minfSize = (minfEnd - minfStart).toInt()

        output.seek(minfStart)
        output.writeInt(minfSize)
        output.seek(minfEnd)
    }
    private fun computeChunkPlan(totalSamples: Int): ChunkPlan {
        val first = 2
        val mid = 6

        val remaining = totalSamples - first
        val middleCount = if (remaining > 0) remaining / mid else 0
        val last = if (remaining > 0) remaining % mid else 0

        val chunkCount =
            1 + middleCount + if (last > 0) 1 else 0

        return ChunkPlan(first, mid, middleCount, last, chunkCount)
    }

    private fun writeStbl(stbl: StblBox, hdlrBox: HdlrBox) {

        val source = sources.find { it.hdlrBox!!.handlerType == hdlrBox.handlerType }
            ?: error("No source track found for handler ${hdlrBox.handlerType}")


        val plan = computeChunkPlan(source.trunEntries)

        val stscEntryCount = if (plan.lastChunkSamples > 0) 3 else 2

        val stsd = utils.writeStsd(stbl.stsdBox)

        val sttsSize = 8 + 4 + 4 + (1 * 8)
        val cttsSize = 8 + 4 + 4 + (source.cttsEntriesCount * 8)
        val stscSize = 8 + 4 + 4 + stscEntryCount * 12
        val stszSize = 8 + 4 + 4 + 4 + (source.trunEntries * 4)
        val stcoSize = 8 + 4 + 4 + (plan.chunkCount * 4)
        val stssSize = 8 + 4 + 4 + (source.keySampleCount * 4)

        // ---------------- VIDEO --------------------
        if (hdlrBox.handlerType == "vide") {
            val totalSize =
                8 + stsd.size + sttsSize + cttsSize + stscSize + stszSize + stcoSize + stssSize

            output.writeInt(totalSize)
            output.write("stbl".toByteArray())
            output.write(stsd)

            source.stts = utils.writeSttsBox(output, 1, sttsSize)
            source.ctts = utils.writeCttsBox(output, source.cttsEntriesCount, cttsSize)
            source.stsc = utils.writeStscBox(output, stscEntryCount, stscSize)
            source.stsz = utils.writeStszBox(output, source.trunEntries, stszSize)
            source.stco = utils.writeStcoBox(output, plan.chunkCount, stcoSize)
            source.stss = utils.writeStssBox(output, source.keySampleCount, stssSize)
        }

        // ---------------- AUDIO --------------------
        if (hdlrBox.handlerType == "soun") {
            val totalSize =
                8 + stsd.size + sttsSize + stscSize + stszSize + stcoSize

            output.writeInt(totalSize)
            output.write("stbl".toByteArray())
            output.write(stsd)

            source.stts = utils.writeSttsBox(output, 1, sttsSize)
            source.stsc = utils.writeStscBox(output, stscEntryCount, stscSize)
            source.stsz = utils.writeStszBox(output, source.trunEntries, stszSize)
            source.stco = utils.writeStcoBox(output, plan.chunkCount, stcoSize)
        }
    }
    fun writeInitialEntries() {
        for (source in sources) {

            val plan = computeChunkPlan(source.trunEntries)

            // ---------------- STTS ----------------
            val duration = if (source.hdlrBox!!.handlerType == "vide") 512 else 1024
            utils.writeSttsEntry(output, source.stts, 0, source.trunEntries, duration)

            // ---------------- STSC ----------------
            // Chunk 1 → 2 samples
            utils.writeStscEntry(output, source.stsc, 0, 1, plan.firstChunkSamples, 1)

            // Middle chunks → 6 samples
            utils.writeStscEntry(output, source.stsc, 1, 2, plan.middleChunkSamples, 1)

            // Last chunk only if remainder exists
            if (plan.lastChunkSamples > 0) {
                utils.writeStscEntry(
                    output,
                    source.stsc,
                    2,
                    2 + plan.middleChunkCount,
                    plan.lastChunkSamples,
                    1
                )
            }
        }
    }

    fun writeSamples(source: DashedParser) {
        utils.writeStcoEntry(output, source.stco, source.stcoIndex, hereSamplesBegins.toInt())



        for (samp in chunk) {

            // === existing STSZ, STSS, CTTS ===
            utils.writeStszEntry(output, source.stsz, source.stszIndex, samp.frameSize)

            if (source.hdlrBox!!.handlerType == "vide") {
                if (samp.isSyncSample) {
                    utils.writeStssEntry(output, source.stss, stssIndex, source.stszIndex + 1)
                    stssIndex++
                }


                val cto = samp.compositionTimeOffset
                if (source.lastOffset == null) {
                    source.lastOffset = cto
                    source.ctoCount = 1
                } else if (cto == source.lastOffset) {
                    source.ctoCount++
                } else {
                    utils.writeCttsEntry(output, source.ctts, source.cttsIndex, source.ctoCount, source.lastOffset!!)
                    source.cttsIndex++
                    source.lastOffset = cto
                    source.ctoCount = 1
                }
            }

            // === Read sample ===
            source.reader.seek(samp.frameAbsOffset)
            val data = ByteArray(samp.frameSize)
            source.reader.readFully(data)

            // === Write MDAT ===
            output.seek(hereSamplesBegins)
            output.write(data)
            hereSamplesBegins += samp.frameSize
            source.stszIndex++

        }

        source.stcoIndex++
        chunk.clear()
    }



    private fun writeMdat() {
        val mdatStart = output.filePointer

        // reset track state
        for (source in sources) {
            source.ctoCount = 0
            source.lastOffset = null
        }

        // write mdat header placeholder
        output.writeInt(0)
        output.write("mdat".toByteArray())

        hereSamplesBegins = output.filePointer
        writeInitialEntries()

        // ---- 1️⃣ Write initial chunks ----
        val active = BooleanArray(sources.size) { true }  // true = this track still has data

        for ((i, source) in sources.withIndex()) {
            val firstChunk = source.getSamples(true)
            if (firstChunk.isNotEmpty()) {
                chunk += firstChunk
                writeSamples(source)
            } else {
                // mark track inactive immediately if initial chunk is empty
                active[i] = false
            }
        }

        // ---- 2️⃣ Proper chunk-round-robin ----
        while (true) {
            var wroteSomething = false

            for (i in sources.indices) {
                if (!active[i]) continue  // skip tracks already finished

                val source = sources[i]
                val nextChunk = source.getSamples(false)

                if (nextChunk.isEmpty()) {
                    // This track is completely finished; deactivate it
                    active[i] = false
                    continue
                }

                wroteSomething = true
                chunk += nextChunk
               writeSamples(source)
            }

            // If NO track produced anything in this whole cycle → all done
            if (!wroteSomething) break
        }

        // ---- 3️⃣ Finalize mdat size ----
        val mdatEnd = output.filePointer
        val mdatSize = (mdatEnd - mdatStart).toInt()

        output.seek(mdatStart)
        output.writeInt(mdatSize)
        output.seek(mdatEnd)
    }






}





