package muxer.mpfour

import muxer.mpfour.models.BoxHeader
import muxer.mpfour.models.MoovBox
import muxer.mpfour.models.Mvhd
import muxer.mpfour.models.trakModels.EdtsBox
import muxer.mpfour.models.trakModels.MdiaBox
import muxer.mpfour.models.trakModels.Tkhd
import muxer.mpfour.models.trakModels.TrakBox
import muxer.mpfour.models.trakModels.edtsModels.ElstBox
import muxer.mpfour.models.trakModels.mdiaModels.HdlrBox
import muxer.mpfour.models.trakModels.mdiaModels.MdhdBox
import muxer.mpfour.models.trakModels.mdiaModels.MinfBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.DinfBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.StblBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Co64
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Ctts
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sbgp
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Sgpd
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stco
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsc
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.StsdBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stss
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stsz
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.Stts
import java.io.DataInput
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MoovParser(
    private val reader: RandomAccessFile,
    private val doLogging: Boolean,

){
    val utils= ReadUtils(reader, doLogging)
    fun parseMoov(boxHeader: BoxHeader): MoovBox {
        val moovEnd =utils.boxEnd(boxHeader)
        reader.seek(boxHeader.payloadOffset)

        var mvhdBox: Mvhd? = null
        val trakBoxes = mutableListOf<TrakBox>()

        while (reader.filePointer + 8 <= moovEnd) {
            val subBox = utils.readBoxHeader() ?: break

            // Safety: boundary check
            if (subBox.startOffset + subBox.size > moovEnd) {
                println("⚠ Invalid sub-box in moov: ${subBox.type} at offset=${subBox.startOffset}")
                break
            }

            if (doLogging) {
                println("Box: ${subBox.type} | Offset=${subBox.startOffset} | Size=${subBox.size}")
            }

            when (subBox.type) {
                "mvhd" -> {
                    mvhdBox = utils.parseMvhd(subBox)
                    utils.skipBox(subBox)
                }
                "trak" -> {
                    val trakBox = parseTrak(subBox)
                    trakBoxes.add(trakBox)
                    utils.skipBox(subBox)
                }


                else -> utils.skipBox(subBox)
            }
        }

        return MoovBox(
            mvhdBox = mvhdBox ?: error("Missing mvhd in moov"),
            trakBoxes = trakBoxes
        )
    }
    fun parseTrak(boxHeader: BoxHeader): TrakBox {
        val trakEnd = boxHeader.payloadOffset + boxHeader.payloadSize
        reader.seek(boxHeader.payloadOffset)

        var tkhdBox: Tkhd? = null
        var mdiaBox: MdiaBox? = null
        var edtsBox: EdtsBox? = null

        while (reader.filePointer + 8 <= trakEnd) {
            val subBox = utils.readBoxHeader() ?: break

            // Boundary check
            if (subBox.startOffset + subBox.size > trakEnd) {
                println("⚠ Invalid sub-box in trak: ${subBox.type} at offset=${subBox.startOffset} size=${subBox.size}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${utils.convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {
                "tkhd" -> {
                    tkhdBox = utils.parseTkhd(subBox)
                    utils.skipBox(subBox)
                }
                "mdia" -> {
                    mdiaBox=parseMdia(subBox)
                    utils.skipBox(subBox)
                }
                "edts" -> {
                    edtsBox=parseEdts(subBox)
                    utils.skipBox(subBox)
                }
                else -> utils.skipBox(subBox)
            }
        }

        // Ensure required boxes exist
        return TrakBox(
            tkhdBox = tkhdBox ?: error("Missing tkhd in trak"),
            mdiaBox = mdiaBox ?: error("Missing mdia in trak"),
            edtsBox = edtsBox
        )
    }
    fun parseUdta(boxHeader: BoxHeader) {
        val udtaEnd = boxHeader.payloadOffset + boxHeader.payloadSize
        reader.seek(boxHeader.payloadOffset)

        while (reader.filePointer + 8 <= udtaEnd) {

            val sub = utils.readBoxHeader() ?: break

            println("udta child: ${sub.type} (size=${sub.size})")

            when (sub.type) {
                "meta" -> {
                    parseMeta(sub)
                    utils.skipBox(sub)

                }   // meta fully parses + skips children

            }

            // IMPORTANT: skip ONLY once
            utils.skipBox(sub)
        }
    }
    fun parseMeta(meta: BoxHeader) {
        val metaEnd = meta.payloadOffset + meta.payloadSize
        reader.seek(meta.payloadOffset)

        // full box header
        val version = reader.readUnsignedByte()
        val flags = (reader.readUnsignedByte() shl 16) or
                (reader.readUnsignedByte() shl 8) or
                reader.readUnsignedByte()

        if (doLogging) println("meta: version=$version flags=$flags")


        while (reader.filePointer + 8 <= metaEnd) {
            val child = utils.readBoxHeader() ?: break

            if (child.startOffset + child.size > metaEnd) {
                println("⚠ meta child exceeds bounds")
                break
            }

            if (doLogging) {
                println(" meta child: ${child.type} | offset=${child.startOffset} size=${child.size}")
            }

            when (child.type) {
                "hdlr" -> {
                    val h = parseHdlr(child.payloadOffset)
                    println("  → hdlr.handlerType = $h")
                    utils.skipBox(child)
                }
                "ilst" -> {
                  println("ilist")
                    parseIlst(child)
                    utils.skipBox(child)
                }
            }

            utils.skipBox(child)
        }
    }
    fun parseIlst(ilst: BoxHeader) {
        val ilstEnd = ilst.payloadOffset + ilst.payloadSize
        reader.seek(ilst.payloadOffset)

        // Walk through metadata entries
        while (reader.filePointer + 8 <= ilstEnd) {
            val item = utils.readBoxHeader() ?: break

            // Debug output - show both type and size
            println("Box type: '${item.type}', size: ${item.size}, offset: ${item.startOffset}")

            // Handle specific known types
            when (item.type) {
                "©too" -> parseTool(item)  // Tool/encoder info
                // Add more known types as needed
                else -> {
                    println("Unknown box type: '${item.type}', skipping")
                    // For debugging, you might want to hex dump unknown boxes
                    // dumpBoxContents(item)
                }
            }

            // Move to next box
            reader.seek(item.startOffset + item.size)
        }
    }

    // Example parser for a specific box type
    private fun parseTool(box: BoxHeader) {
        reader.seek(box.payloadOffset)
        // Read null-terminated string
        val tool = reader.readNullTerminatedString()
        println("Tool: $tool")
    }

    // Helper extension for reading null-terminated strings
    fun DataInput.readNullTerminatedString(): String {
        val bytes = mutableListOf<Byte>()
        var byte = readByte()
        while (byte != 0.toByte()) {
            bytes.add(byte)
            byte = readByte()
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
    fun parseHdlr(payloadOffset: Long): String {
        reader.seek(payloadOffset)

        val version = reader.readUnsignedByte()
        reader.skipBytes(3)      // flags
        reader.skipBytes(4)      // pre_defined

        val handlerType = reader.readAscii(4)


        return handlerType
    }
    fun RandomAccessFile.readAscii(count: Int): String {
        val bytes = ByteArray(count)
        this.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
    private fun parseMdia(boxHeader: BoxHeader): MdiaBox {
        val mdiaEnd = boxHeader.payloadOffset + boxHeader.payloadSize
        reader.seek(boxHeader.payloadOffset)

        var mdhdBox: MdhdBox? = null
        var hdlrBox: HdlrBox? = null
        var minfBox: MinfBox? = null

        while (reader.filePointer + 8 <= mdiaEnd) {
            val subBox = utils.readBoxHeader() ?: break

            // Validate boundary
            if (subBox.startOffset + subBox.size > mdiaEnd) {
                println("⚠ Invalid sub-box in mdia: ${subBox.type} at offset=${subBox.startOffset} size=${subBox.size}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${utils.convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {
                "mdhd" -> {
                    mdhdBox = utils.parseMdhd(subBox)
                    utils.skipBox(subBox)
                }
                "hdlr" -> {
                    hdlrBox = utils.parseHdlr( subBox)
                    utils.skipBox(subBox)
                }
                "minf" -> {
                    minfBox=parseMinf(subBox)
                    utils.skipBox(subBox)
                }
                else -> utils.skipBox(subBox)
            }
        }

        return MdiaBox(
            mdhdBox = mdhdBox ?: error("Missing mdhd in mdia"),
            hdlrBox = hdlrBox ?: error("Missing hdlr in mdia"),
            minfBox = minfBox
        )
    }
    fun parseEdts(boxHeader: BoxHeader): EdtsBox {
        val edtsEnd = utils.boxEnd(boxHeader)
        reader.seek(boxHeader.payloadOffset)

        var elstBox: ElstBox? = null
        val rawbox= ByteArray(boxHeader.payloadSize.toInt())
        reader.readFully(rawbox)
        reader.seek(boxHeader.payloadOffset)

        while (reader.filePointer + 8 <= edtsEnd) {
            val subBox = utils.readBoxHeader() ?: break

            // Boundary safety
            if (subBox.startOffset + subBox.size > edtsEnd) {
                println("⚠ Invalid sub-box in edts: ${subBox.type} at offset=${subBox.startOffset} size=${subBox.size}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${utils.convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {
                "elst" -> {
                    elstBox = utils.parseElst(subBox.payloadOffset)
                    utils.skipBox(subBox)
                }
                else -> utils.skipBox(subBox)
            }
        }

        return EdtsBox(
            elstBox = elstBox,
            raw = rawbox
        )
    }

    private fun readFullBoxBytes(header: BoxHeader): ByteArray {
        val size = header.size
        reader.seek(header.startOffset)
        val data = ByteArray(size.toInt())
        reader.readFully(data)
        return data
    }


    private fun parseMinf(boxHeader: BoxHeader): MinfBox {
        val minfEnd = boxHeader.payloadOffset + boxHeader.payloadSize
        reader.seek(boxHeader.payloadOffset)

        var mediaHandler: ByteArray? = null
        var dinfBox: DinfBox? = null
        var stblBox: StblBox? = null

        while (reader.filePointer + 8 <= minfEnd) {
            val subBox = utils.readBoxHeader() ?: break

            if (subBox.startOffset + subBox.size > minfEnd) {
                println("⚠ Invalid sub-box in minf: ${subBox.type} at offset=${subBox.startOffset} size=${subBox.size}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${utils.convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {

                // === Capture any handler-specific minf box ===
                "vmhd", "smhd", "hmhd", "nmhd", "gmhd" -> {
                    mediaHandler = readFullBoxBytes(subBox)
                    utils.skipBox(subBox)
                }

                "dinf" -> {
                    dinfBox = utils.parseDinf(subBox)
                    utils.skipBox(subBox)
                }

                "stbl" -> {
                    stblBox = parseStbl(subBox)
                    utils.skipBox(subBox)
                }

                else -> utils.skipBox(subBox)
            }
        }


        // Validation before returning
        return MinfBox(

            dinfBox = dinfBox ?: error("Missing dinf in minf"),
            stblBox = stblBox ?: error("Missing stbl in minf"),
            mediaHandler = mediaHandler?: error("Missing stbl in minf")
        )
    }
    private fun parseStbl(boxHeader: BoxHeader): StblBox {
        val stblEnd = boxHeader.payloadOffset + boxHeader.payloadSize
        reader.seek(boxHeader.payloadOffset)

        var stsdBox: StsdBox? = null
        var stts: Stts? = null
        var stsc: Stsc? = null
        var stsz: Stsz? = null
        var stss: Stss? = null
        var stco: Stco? = null
        var co64: Co64? = null
        var ctts: Ctts? = null
        var sgpd: Sgpd? = null
        var sbgp: Sbgp? = null

        while (reader.filePointer + 8 <= stblEnd) {
            val subBox = utils.readBoxHeader() ?: break

            if (subBox.startOffset + subBox.size > stblEnd) {
                println("⚠ Invalid sub-box in stbl: ${subBox.type} at offset=${subBox.startOffset}")
                break
            }

            if (doLogging) {
                println(
                    "Box: ${subBox.type} | BoxOffset: ${subBox.startOffset} | BoxSize: ${subBox.size} | " +
                            "PayloadOffset: ${subBox.payloadOffset} | PayloadSize: ${utils.convertBytes(subBox.payloadSize)}"
                )
            }

            when (subBox.type) {

                "stsd" -> {
                    reader.seek(subBox.startOffset)
                    val raw = ByteArray(subBox.size.toInt())
                    reader.readFully(raw)
                    stsdBox = parseStsd(raw, subBox.startOffset, subBox.size)
                }

                "stts" -> stts = utils.parseSttsEntries(subBox)
                "stsc" -> stsc = utils.parseStscEntries(subBox)
                "stsz" -> stsz = utils.parseStszEntries(subBox)
                "stss" -> stss = utils.parseStssEntries(subBox)

                // Prefer co64 if present
                "co64" -> {
                    co64 = utils.parseCo64Entries(subBox)
                    stco = null   // ensure we don't accidentally use stco
                }

                // Only parse stco if co64 not present
                "stco" -> {
                    if (co64 == null) {
                        stco = utils.parseStcoEntries(subBox)
                    }
                }

                "ctts" -> ctts = utils.parseCttsEntries(subBox)
                "sgpd" -> sgpd = utils.parseSgpdEntries(subBox)
                "sbgp" -> sbgp = utils.parseSbgpEntries(subBox)

                else -> utils.skipBox(subBox)
            }

            utils.skipBox(subBox)
        }

        // --- Validation ---
        if (co64 == null && stco == null) {
            error("Missing stco/co64 in stbl")
        }

        return StblBox(
            stsdBox = stsdBox ?: error("Missing stsd in stbl"),
            stts = stts ?: error("Missing stts in stbl"),
            stsc = stsc ?: error("Missing stsc in stbl"),
            stsz = stsz ?: error("Missing stsz in stbl"),
            stss = stss ?: Stss(0, 0, ByteArray(1)),
            stco = stco,      // can be null
            co64 = co64,      // can be null
            ctts = ctts,
            sgpd = sgpd,
            sbgp = sbgp
        )

    }

    fun parseStsd(fullStsd: ByteArray, boxOffset: Long, boxSize: Long): StsdBox {
        val buf = ByteBuffer.wrap(fullStsd).order(ByteOrder.BIG_ENDIAN)

        // Skip stsd header (size + 'stsd' + version/flags)
        buf.position(8 + 4)

        val entryCount = buf.int
        require(entryCount >= 1) { "stsd contains no sample entries" }

        // --- Parse sample entry box ---
        val entrySize = buf.int
        val entryType = String(fullStsd, buf.position(), 4)
        buf.position(buf.position() + 4) // skip type

        return when (entryType) {

            // =========================
            // ===== AVC1 (video) ======
            // =========================
            "avc1" -> {
                // Skip fixed fields (78 bytes)
                buf.position(buf.position() + 78)

                // Parse avcC
                val avcCSize = buf.int
                val avcCType = String(fullStsd, buf.position(), 4)
                require(avcCType == "avcC") { "Expected avcC in avc1, found $avcCType" }
                buf.position(buf.position() + 4)

                val avcCPayload = ByteArray(avcCSize - 8)
                buf.get(avcCPayload)

                StsdBox(
                    boxOffset = boxOffset,
                    boxSize = boxSize,
                    raw = fullStsd,
                    avcC = avcCPayload
                )
            }

            // =========================
            // ===== MP4A (audio) ======
            // =========================
            "mp4a" -> {
                // Skip AudioSampleEntry fields (28 bytes)
                buf.position(buf.position() + 28)

                // mp4a contains esds box, NOT avcC – we skip it
                // but return empty avcC as requested
                StsdBox(
                    boxOffset = boxOffset,
                    boxSize = boxSize,
                    raw = fullStsd,
                    avcC = ByteArray(0) // *** Return empty for audio track ***
                )
            }

            // ================================
            // ===== Unknown entry type =======
            // ================================
            else -> {
                StsdBox(
                    boxOffset = boxOffset,
                    boxSize = boxSize,
                    raw = fullStsd,
                    avcC = ByteArray(0) // safe default
                )
            }
        }
    }


}