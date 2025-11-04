package mpfour

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer


class DashedWriter(file: File, val sources: List<DashedParser>, val progress: (percent: String) -> Unit = { _-> }){


    val output= RandomAccessFile(file,"rw")
    val mp4Utils= utils()

    fun buildNonFMp4(){
        val ftyp = mp4Utils.writeFtyp()

        // Begin writing to file
        output.seek(0)
        output.write(ftyp)

        //reserve space or write flase data for moov box and its header size and type

        /*val moovStart = output.filePointer
        putInt(totalSize)
        put("moov".toByteArray())*/

        val ts = sources.sumOf { it.trunEntries }
        val estimatedMoovSize= estimateMoovSize(ts)
        val moovReserveSize = estimatedMoovSize
        val moovPosition = output.filePointer
        output.write(ByteArray(moovReserveSize))




        writeMdat()

        val trakList = mutableListOf<ByteArray>()

        //writting moov atom
        for (source in sources){
            val stsc = mp4Utils.writeStsc(source.samplesPerChunkList)
            val stsz = mp4Utils.writeStsz(source.samplesSizes)
            val stco = mp4Utils.writeStco(source.chunksOffsets)

            val drefBox = ByteBuffer.allocate(28).apply {
                putInt(28); put("dref".toByteArray())
                putInt(0); putInt(1) // version/flags and entry count
                putInt(12); put("url ".toByteArray()); putInt(1) // version/flags = self-contained
            }.array()

            val dinfBox = ByteBuffer.allocate(36).apply {
                putInt(36); put("dinf".toByteArray())
                put(drefBox)
            }.array()

            val hdlr=mp4Utils.writeHdlr(source.handlerType)


            if (source.handlerType=="soun"){
                val stts = mp4Utils.writeStts(source.samplesSizes.size, 1024)
                val stbl = mp4Utils.writeStbl(source.stsdBox!!, stts, stsc, stsz, stco)
                val vmhd=mp4Utils.writeSmhd()

                val minfBox=mp4Utils.writeMinf(
                    vmhdOrSmhd = vmhd,
                    dinfBox = dinfBox,
                    stblBox = stbl
                )
                val  mdhd=mp4Utils.writeMdhd(
                    timeScale = source.mediaTimescale,
                    duration = source.mediaDuration.toInt()
                )
                val media=mp4Utils.writeMdia(
                    mdhd = mdhd,
                    hdlr = hdlr,
                    minf = minfBox
                )
                val tkhd = mp4Utils.writeTkhd(trackId =2, duration = source.trackDuration.toInt(), widthPixels = source.videoInfo.width, heightPixels = source.videoInfo.height)


                val track=mp4Utils.writeTrak(
                    tkhd = tkhd,
                    mdia = media
                )
                trakList.add(track)

            }else{
                val stts = mp4Utils.writeStts(source.samplesSizes.size, 512)
                val stss = mp4Utils.writeStss(source.keySamplesIndices)
                val ctts = mp4Utils.writeCtts(source.cttsEntries)
                val stbl = mp4Utils.writeStbl(source.stsdBox!!, stts, stsc, stsz, stco, stss, ctts)

                val vmhd=mp4Utils.writeVmhd()

                val minfBox=mp4Utils.writeMinf(
                    vmhdOrSmhd = vmhd,
                    dinfBox = dinfBox,
                    stblBox = stbl
                )
                val  mdhd=mp4Utils.writeMdhd(
                    timeScale = source.mediaTimescale,
                    duration = source.mediaDuration.toInt()
                )
                val media=mp4Utils.writeMdia(
                    mdhd = mdhd,
                    hdlr = hdlr,
                    minf = minfBox
                )
                val tkhd = mp4Utils.writeTkhd(trackId = 1, duration = source.trackDuration.toInt(), widthPixels = source.videoInfo.width, heightPixels = source.videoInfo.height)


                val track=mp4Utils.writeTrak(
                    tkhd = tkhd,
                    mdia = media
                )
                trakList.add(track)
            }
        }


        val movieTimeScale = 1000
        val maxTrack = sources.maxBy { it.trackDuration.toDouble() / it.mediaTimescale }

        val mvhdDuration = (maxTrack.trackDuration * movieTimeScale) / maxTrack.mediaTimescale

        val mvhd = mp4Utils.writeMvhd(
            timeScale = movieTimeScale,
            duration = mvhdDuration.toInt(),
            nextTrack = sources.size + 1
        )


        val moovBox=mp4Utils.writeMoov(mvhd,trakList)


        output.seek(moovPosition)
        output.write(moovBox)
        output.close()
        progress("Finished")

    }

    fun writeMdat() {
        val mdatStart = output.filePointer

        // Write placeholder for size + "mdat"
        output.writeInt(0)
        output.write("mdat".toByteArray())

        for (source in sources){
            val samples=source.getSamples(false)
            source.samplesPerChunkList.add(samples.size)
            source.chunksOffsets.add(output.filePointer)
            for (samp in samples){

                source.samplesSizes.add(samp.frameSize)
                source.reader.seek(samp.frameAbsOffset)
                val data = ByteArray(samp.frameSize)
                source.reader.readFully(data)
                output.write(data)

                if (source.handlerType=="vide"){
                    if (samp.isSyncSample) {
                        source.keySamplesIndices.add(source.samplesSizes.size)
                        // CTTS run-length logic
                        val cto = samp.compositionTimeOffset
                        if (source.lastOffset == null) {
                            source.lastOffset = cto
                            source.runLength = 1
                        } else if (cto == source.lastOffset) {
                            source.runLength++
                        } else {
                            source.cttsEntries.add(source.runLength to source.lastOffset!!)
                            source.lastOffset = cto
                            source.runLength = 1
                        }
                    }

                }
            }
        }



        val mdatEnd = output.filePointer
        val sizeLong = mdatEnd - mdatStart
        require(sizeLong <= Int.MAX_VALUE) { "mdat too large: $sizeLong bytes" }

        val size = sizeLong.toInt()
        output.seek(mdatStart)
        output.writeInt(size)
        output.seek(mdatEnd)
        println("✅ Samples written. mdat size: $size bytes")
    }
}

/*ffmpeg -i output.mp4 -c copy fixed.mp4
*/

