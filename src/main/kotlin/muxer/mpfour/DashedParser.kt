package muxer.mpfour


import muxer.mpfour.models.BoxHeader
import muxer.mpfour.models.TrunSampleEntry
import java.io.RandomAccessFile
import java.util.Collections.emptyList

class DashedParser(val reader: RandomAccessFile,val doLogging: Boolean,val startOffset: Long,val endOffset: Long){
    val utils= ReadUtils(reader, doLogging)
    var brandInfo: muxer.mpfour.models.BrandInfo?=null
    var moovBox: muxer.mpfour.models.MoovBox? = null
        private set
    var hdlrBox: muxer.mpfour.models.trakModels.mdiaModels.HdlrBox?=null
        private set

    private var currentMoof: MoofParser?=null
    private var firstMoofInfo: BoxHeader?=null
    // ======= stbl data builders =======
    var trunEntries=0
    var keySampleCount=0
    var cttsEntriesCount=0

    var lastOffset: Int? = null
    var runLength = 0
    var reachedEof=false

    //will be updated and used by writer
    var stts=-1L
    var ctts=-1L
    var stsc=-1L
    var stsz=-1L
    var stco=-1L
    var stss=-1L

    //indexes will be updated and used by writer
    var stcoIndex=0
    var stssIndex=0
    var stszIndex=0
    var cttsIndex=0
    var mdat: muxer.mpfour.models.BoxHeader?=null

    // in class DashedParser
    var ctoCount: Int = 0

    var decodeTime = 0L          // running DTS clock


    private var lastSamplesPerChunk: Int? = null
    private var stscEntryCount = 0





    init {
       parse()
    }
    private fun parse(){
        reader.seek(startOffset)

        val header = utils.readBoxHeader() ?: throw IllegalStateException("Empty or invalid file")
        if (header.type != "ftyp" && header.type != "styp") {
            throw IllegalStateException("Invalid container: expected 'ftyp' or 'styp', found '${header.type}'")
        }

        if (doLogging)
            println("Valid ISO header found: '${header.type}' at offset ${header.startOffset} (size=${header.size})")

        reader.seek(startOffset)

        while (reader.filePointer + 8 <= endOffset) {

            val box = utils.readBoxHeader() ?: break
            if (doLogging)
                println("Box: ${box.type} | Offset=${box.startOffset} | Size=${box.size}")

            when (box.type) {
                "ftyp" -> {
                    brandInfo=utils.getBrands(box = box)
                    if (doLogging){
                        println("Brand Info: Major ${brandInfo?.majorBrand}  Major version: ${brandInfo?.minorVersion}")
                        println("======Compatible Brands======")
                        for (brand in brandInfo!!.compatibleBrands){
                            println(brand)
                        }
                    }
                    utils.skipBox(box)
                }
                "moov" -> {
                    val moovParser= MoovParser(reader,doLogging)
                    moovBox=moovParser.parseMoov(box)
                    if (moovBox!!.trakBoxes.size==1){
                        hdlrBox=moovBox!!.trakBoxes[0].mdiaBox.hdlrBox
                    }
                    utils.skipBox(box)
                }
                "moof" -> {
                    moofParser(box)
                    utils.skipBox(box)

                }
                "mdat"->{
                    mdat=box
                    utils.skipBox(box)
                }
                else -> {
                    // Skip unknown or irrelevant boxes
                    utils.skipBox(box)
                }
            }
        }
        if (lastOffset != null && runLength > 0) {
           cttsEntriesCount++
        }

    }
    fun moofParser(boxHeader: BoxHeader) {

        if (firstMoofInfo == null) {
            firstMoofInfo = boxHeader
        }

        val moofParser = MoofParser(reader, boxHeader.payloadOffset, boxHeader.payloadSize,doLogging)
        val entries = moofParser.getEntries(Int.MAX_VALUE)

        trunEntries += entries.size        // ✔ still counts total samples


        // ----------------------------------------
        //           STSC DETECTOR (FIXED)
        // ----------------------------------------


        val currentSamplesPerChunk = entries.size       // ✔ samples for this chunk


        if (lastSamplesPerChunk == null) {
            stscEntryCount++
            lastSamplesPerChunk = currentSamplesPerChunk

        } else if (currentSamplesPerChunk != lastSamplesPerChunk) {
            stscEntryCount++
            lastSamplesPerChunk = currentSamplesPerChunk
        }
        // ----------------------------------------


        // ========================================
        //         YOUR EXISTING LOGIC
        // ========================================
        if (moovBox != null) {
            if (moovBox!!.trakBoxes[0].mdiaBox.hdlrBox.handlerType == "vide") {
                for (entry in entries) {
                    if (entry.isSyncSample) keySampleCount++

                    val sampleDuration = entry.duration ?: 0
                    val cto = entry.compositionTimeOffset ?: 0

                    decodeTime += sampleDuration

                    if (lastOffset == null) {
                        lastOffset = cto
                        runLength = 1
                    } else if (cto == lastOffset) {
                        runLength++
                    } else {
                        cttsEntriesCount++
                        lastOffset = cto
                        runLength = 1
                    }
                }
            }
        }
    }


    private val pendingEntries = mutableListOf<TrunSampleEntry>()



    fun getSamples(initialChunk: Boolean): MutableList<TrunSampleEntry> {
        val targetSamples = if (initialChunk) 2 else 6
        if (reachedEof) return emptyList()

        val output = mutableListOf<TrunSampleEntry>()

        // ---- Step 1: use what is already collected ----
        if (pendingEntries.isNotEmpty()) {
            val toTake = minOf(targetSamples, pendingEntries.size)
            output += pendingEntries.take(toTake)
            repeat(toTake) { pendingEntries.removeAt(0) }

            if (output.size == targetSamples)
                return output
        }

        // ---- Step 2: pull from current moof, if exists ----
        if (currentMoof == null) {
            currentMoof = MoofParser(reader, firstMoofInfo!!.payloadOffset, firstMoofInfo!!.payloadSize,doLogging)
        }

        var newEntries = currentMoof!!.getEntries(targetSamples - output.size)

        // Store what we got
        output += newEntries

        // ---- If enough samples → return immediately ----
        if (output.size == targetSamples)
            return output

        // ---- Step 3: Not enough → load NEXT moof(s) ----
        loadNextMoofLoop@ while (!reachedEof && output.size < targetSamples) {

            // Skip old moof
            utils.skipBox(firstMoofInfo!!)
            currentMoof = null
            firstMoofInfo = null

            // Find next moof
            while (reader.filePointer + 8 <= endOffset) {
                val box = utils.readBoxHeader() ?: break

                when (box.type) {
                    "moof" -> {
                        firstMoofInfo = box
                        currentMoof = MoofParser(reader, box.payloadOffset, box.payloadSize,doLogging)
                        break
                    }
                    "mdat" -> utils.skipBox(box)
                    else -> utils.skipBox(box)
                }
            }

            if (reader.filePointer >= endOffset) {
                reachedEof = true
                break
            }

            // If no more moofs found → EOF
            if (currentMoof == null || firstMoofInfo == null) {
                reachedEof = true
                break
            }

// Read from new moof
            val needed = targetSamples - output.size
            newEntries = currentMoof!!.getEntries(needed)
            output += newEntries

        }

        // ---- Step 4: If extra entries were collected, store them ----
        if (output.size > targetSamples) {
            pendingEntries += output.subList(targetSamples, output.size)
            return output.subList(0, targetSamples)
        }

        // ---- Step 5: Return what we have (EOF case) ----
        return output
    }



}




