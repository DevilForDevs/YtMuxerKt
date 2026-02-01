package muxer.mpfour


import muxer.mpfour.models.BrandInfo
import muxer.mpfour.models.Mvhd
import muxer.mpfour.models.trakModels.mdiaModels.HdlrBox
import muxer.mpfour.models.trakModels.mdiaModels.MdhdBox
import muxer.mpfour.models.trakModels.mdiaModels.minfModels.stblModels.StsdBox
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WriteUtils(

){
    fun writeFtyp(): ByteArray {
        val majorBrand = "isom"
        val minorVersion = 512 // 0x00000200
        val compatibleBrands = listOf("isom", "iso2", "avc1", "mp41")

        val size = 8 + 4 + 4 + compatibleBrands.size * 4 // header + major/minor + compatible

        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(size)
            put("ftyp".toByteArray(Charsets.US_ASCII))
            put(majorBrand.toByteArray(Charsets.US_ASCII))
            putInt(minorVersion)
            compatibleBrands.forEach { put(it.toByteArray(Charsets.US_ASCII)) }
        }.array()
    }

    fun writeFtyp(brand: BrandInfo?): ByteArray {
        val major = "mp42"
        val minor = 0
        val compat = listOf("isom", "mp42")

        val compBytes = compat.joinToString("") { it }.toByteArray(Charsets.US_ASCII)
        val size = 8 + 4 + 4 + compBytes.size

        return ByteBuffer.allocate(size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(size)
                put("ftyp".toByteArray())
                put(major.toByteArray())
                putInt(minor)
                put(compBytes)
            }.array()
    }

    fun buildEdtsBox(segmentDuration: Int,skip: Int): ByteArray {
        val elstPayloadSize =
            4 + // version + flags
                    4 + // entry_count
                    4 + // segment_duration
                    4 + // media_time (signed)
                    2 + // media_rate_integer
                    2   // media_rate_fraction

        val elstBoxSize = 8 + elstPayloadSize
        val edtsBoxSize = 8 + elstBoxSize

        val bb = ByteBuffer.allocate(edtsBoxSize).order(ByteOrder.BIG_ENDIAN)

        // ----- edts -----
        bb.putInt(edtsBoxSize)
        bb.put("edts".toByteArray(Charsets.US_ASCII))

        // ----- elst -----
        bb.putInt(elstBoxSize)
        bb.put("elst".toByteArray(Charsets.US_ASCII))

        // version = 0, flags = 0
        bb.put(0)
        bb.put(ByteArray(3))

        // entry_count = 1
        bb.putInt(1)

        // Entry #1
        bb.putInt(segmentDuration) // segment_duration

        // media_time = -512 (SIGNED INT!)
        bb.putInt(512)

        // rate = 1.0
        bb.putShort(1)
        bb.putShort(0)

        return bb.array()
    }


    fun writeMvhd(mvhd: Mvhd,timeScale:Int?,duration: Int?): ByteArray {
        val size = 8 + 100
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        val creationTime = mp4EpochNow()

        bb.putInt(size)
        bb.put("mvhd".toByteArray(Charsets.US_ASCII))
        bb.put(0)
        bb.put(ByteArray(3))

        bb.putInt(creationTime)
        bb.putInt(creationTime)

        bb.putInt(timeScale?:mvhd.timeScale)
        bb.putInt(duration?:mvhd.duration.toInt())

        bb.putInt(0x00010000)
        bb.putShort(0x0100.toShort())
        bb.putShort(0)
        bb.putInt(0)
        bb.putInt(0)

        bb.putInt(0x00010000); bb.putInt(0); bb.putInt(0)
        bb.putInt(0); bb.putInt(0x00010000); bb.putInt(0)
        bb.putInt(0); bb.putInt(0); bb.putInt(0x40000000)

        bb.putInt(2)  // nextTrackID

        return bb.array()
    }


    fun mp4EpochNow(): Int {
        val qtEpoch = 2082844800L   // 1904-01-01 → Unix epoch offset
        val now = System.currentTimeMillis() / 1000
        return (now + qtEpoch).toInt()
    }
    fun writeTkhd(duration: Long, width: Int, height: Int): ByteArray {
        val size = 104
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(size)                     // box size
        buffer.put("tkhd".toByteArray())        // type

        buffer.put(1)                            // version = 1
        buffer.put(byteArrayOf(0x00, 0x00, 0x07)) // flags = enabled | movie | preview

        val macTime = (System.currentTimeMillis() / 1000) + 2082844800L

        buffer.putLong(macTime)                 // creation_time
        buffer.putLong(macTime)                 // modification_time

        buffer.putInt(1)                        // track_ID
        buffer.putInt(0)                        // reserved

        buffer.putLong(duration)                // duration 64-bit

        buffer.putInt(0)                        // reserved1
        buffer.putInt(0)                        // reserved2

        buffer.putShort(0)                      // layer
        buffer.putShort(0)                      // alternate group
        buffer.putShort(0)                      // volume (0 for video)
        buffer.putShort(0)                      // reserved

        // Identity matrix
        val matrix = intArrayOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        matrix.forEach { buffer.putInt(it) }

        buffer.putInt(width shl 16)             // width 16.16
        buffer.putInt(height shl 16)            // height 16.16

        return buffer.array()
    }

    fun writeMdhd(mdhd: MdhdBox,timeScale: Int?,duration: Int?): ByteArray {
        val nowSeconds = mp4EpochNow()

        val size = 32
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        bb.putInt(size)
        bb.put("mdhd".toByteArray(Charsets.US_ASCII))
        bb.put(0)                      // version (0 = 32-bit)
        bb.put(ByteArray(3))           // flags

        bb.putInt(nowSeconds)          // creation_time
        bb.putInt(nowSeconds)          // modification_time

        bb.putInt(timeScale?:mdhd.timescale.toInt())
        bb.putInt(duration?:mdhd.duration.toInt())

        bb.putShort(encodeLanguage(mdhd.language).toShort())
        bb.putShort(0)

        return bb.array()
    }



    private fun encodeLanguage(code: String): Int {
        if (code.length != 3) return 0x55C4 // default "und" (undefined)
        val c1 = (code[0].code - 0x60) and 0x1F
        val c2 = (code[1].code - 0x60) and 0x1F
        val c3 = (code[2].code - 0x60) and 0x1F
        return (c1 shl 10) or (c2 shl 5) or c3
    }


    fun writeHdlr(hdlr: HdlrBox): ByteArray {
        val nameBytes = hdlr.handlerName.toByteArray(Charsets.US_ASCII)
        val size = 8 + 24 + nameBytes.size + 1 // +1 null terminator
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(size)
        bb.put("hdlr".toByteArray())
        bb.put(0)
        bb.put(ByteArray(3))
        bb.putInt(0)
        bb.put(hdlr.handlerType.toByteArray(Charsets.US_ASCII))
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.put(nameBytes)
        bb.put(0)
        return bb.array()
    }
    fun writeStsd(stsd: StsdBox?): ByteArray {
        requireNotNull(stsd) { "Missing stsd box — cannot write stbl" }
        return stsd.raw!!
    }

    fun writeSttsBox(output: RandomAccessFile, entryCount: Int,size: Int): Long{
        val sttsOffset = output.filePointer
        output.writeInt(size)
        output.write("stts".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version + flags
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 8)) // placeholder for entries (sample_count + sample_delta)
        return sttsOffset

    }
    fun writeSttsEntry(output: RandomAccessFile, sttsOffset: Long, entryIndex: Int, sampleCount: Int, sampleDelta: Int) {
        // Skip header: size(4) + type(4) + version+flags(4) + entry_count(4) = 16 bytes
        val entriesStart = sttsOffset + 16
        val entryOffset = entriesStart + entryIndex * 8L  // each entry = 8 bytes

        output.seek(entryOffset)
        output.writeInt(sampleCount)
        output.writeInt(sampleDelta)
    }

    fun writeCttsBox(output: RandomAccessFile, entryCount: Int, size: Int): Long {
        val cttsOffset = output.filePointer
        output.writeInt(size)
        output.write("ctts".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version + flags
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 8)) // placeholder for entries (sample_count + sample_offset)

        return cttsOffset
    }
    fun writeCttsEntry(
        output: RandomAccessFile,
        cttsOffset: Long,
        entryIndex: Int,
        sampleCount: Int,
        sampleOffset: Int
    ) {
        // ctts header = size(4) + type(4) + version+flags(4) + entry_count(4)
        val entriesStart = cttsOffset + 16
        val entryOffset = entriesStart + entryIndex * 8L // 8 bytes per entry

        output.seek(entryOffset)
        output.writeInt(sampleCount)
        output.writeInt(sampleOffset)
    }

    fun writeStscBox(output: RandomAccessFile, entryCount: Int, size: Int): Long {
        val stscOffset = output.filePointer
        output.writeInt(size)
        output.write("stsc".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version + flags
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 12)) // placeholder for entries (3 ints per entry)

        return stscOffset
    }
    fun writeStscEntry(
        output: RandomAccessFile,
        stscOffset: Long,
        entryIndex: Int,
        firstChunk: Int,
        samplesPerChunk: Int,
        sampleDescriptionIndex: Int
    ) {
        // Header: size(4) + type(4) + version+flags(4) + entry_count(4) = 16 bytes
        val entriesStart = stscOffset + 16
        val entryOffset = entriesStart + entryIndex * 12L // each entry = 12 bytes

        output.seek(entryOffset)
        output.writeInt(firstChunk)
        output.writeInt(samplesPerChunk)
        output.writeInt(sampleDescriptionIndex)
    }

    fun writeStszBox(output: RandomAccessFile, entryCount: Int,size: Int): Long {

        val stszOffset = output.filePointer
        output.writeInt(size)
        output.write("stsz".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version+flags
        output.writeInt(0) // sample_size (0 => variable)
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 4))
        return stszOffset

    }

    fun writeStszEntry(
        output: RandomAccessFile,
        stszOffset: Long,
        entryIndex: Int,
        sampleSize: Int
    ) {
        // stsz:
        // [0..3] size
        // [4..7] "stsz"
        // [8..11] version+flags
        // [12..15] sample_size (0 => variable)
        // [16..19] sample_count
        // [20..] entry_sizes (4 bytes each)
        val entryOffset = stszOffset + 20 + (entryIndex * 4L)
        output.seek(entryOffset)
        output.writeInt(sampleSize)
    }
    fun writeStcoBox(output: RandomAccessFile, entryCount: Int, size: Int): Long {
        val stcoOffset = output.filePointer
        output.writeInt(size)
        output.write("stco".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version + flags
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 4)) // placeholder for chunk_offset entries

        return stcoOffset
    }
    fun writeStcoEntry(output: RandomAccessFile, stcoOffset: Long, entryIndex: Int, chunkOffset: Int) {
        val originalPos = output.filePointer
        // Header: size(4) + type(4) + version+flags(4) + entry_count(4) = 16 bytes
        val entriesStart = stcoOffset + 16
        val entryOffset = entriesStart + entryIndex * 4L // each entry = 4 bytes

        output.seek(entryOffset)
        output.writeInt(chunkOffset)
        output.seek(originalPos)
    }

    fun writeStssBox(output: RandomAccessFile, entryCount: Int, size: Int): Long {
        val stssOffset = output.filePointer
        output.writeInt(size)
        output.write("stss".toByteArray(Charsets.US_ASCII))
        output.writeInt(0) // version + flags
        output.writeInt(entryCount)
        output.write(ByteArray(entryCount * 4)) // placeholder for sample_number entries

        return stssOffset
    }
    fun writeStssEntry(output: RandomAccessFile, stssOffset: Long, entryIndex: Int, sampleNumber: Int) {
        // Header: size(4) + type(4) + version+flags(4) + entry_count(4) = 16 bytes
        val entriesStart = stssOffset + 16
        val entryOffset = entriesStart + entryIndex * 4L // each entry = 4 bytes

        output.seek(entryOffset)
        output.writeInt(sampleNumber)
        output.seek(stssOffset)
    }
    private fun writeEmptyNmhd(output: RandomAccessFile) {
        output.writeInt(12)                    // size
        output.write("nmhd".toByteArray())     // type
        output.writeByte(0)                    // version
        output.write(byteArrayOf(0,0,0))       // flags
    }

    private fun writeEmptySmhd(output: RandomAccessFile) {
        output.writeInt(16)                    // size
        output.write("smhd".toByteArray())     // type
        output.writeByte(0)                    // version
        output.write(byteArrayOf(0,0,0))       // flags
        output.writeShort(0)                   // balance
        output.writeShort(0)                   // reserved
    }

    private fun writeEmptyVmhd(output: RandomAccessFile) {
        output.writeInt(20)                    // size
        output.write("vmhd".toByteArray())     // type
        output.writeByte(0)                    // version
        output.write(byteArrayOf(0,0,1))       // flags = 1
        output.writeShort(0)                   // graphicsmode
        output.writeShort(0)                   // opcolor R
        output.writeShort(0)                   // opcolor G
        output.writeShort(0)                   // opcolor B
    }


    fun writeEmptyHandler(handlerType: String,output: RandomAccessFile) {
        when (handlerType) {

            "vide" -> writeEmptyVmhd(output)
            "soun" -> writeEmptySmhd(output)

            // default for text, metadata, captions
            else -> writeEmptyNmhd(output)
        }
    }




}

fun runFfmpegFullLog(outputFile: File) {
    val command = listOf(
        "cmd", "/c",
        "start", "cmd", "/k", // open a NEW cmd window and keep it open
        "ffmpeg",
        "-v", "error",
        "-i", outputFile.absolutePath,
        "-f", "null", "-"    // send output to null, process stays alive if errors exist
    )

    ProcessBuilder(command)
        .directory(File(System.getProperty("user.dir")))
        .start()
}

fun printPts(outputFile: File) {
    val command = listOf(
        "ffprobe",
        "-show_packets",
        "-select_streams", "v",
        outputFile.absolutePath
    )

    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            if (line.contains("pts", ignoreCase = true)) {
                println(line)
            }
        }
    }

    process.waitFor()
}




fun debugUsingFffmpeg(outputFile: File){
    val ffprobeCmd = listOf(
        "cmd", "/c", "start", "cmd", "/k",  // open new terminal and keep it open
        "ffprobe",
        "-hide_banner",
        "-v", "trace",
        outputFile.absolutePath
    )
    ProcessBuilder(ffprobeCmd)
        .directory(File(System.getProperty("user.dir"))) // optional working dir
        .start()
}
fun convertFtoNonFmp4(
    inputPath: String,
    outputPath: String,
    overwrite: Boolean = true
): Boolean {
    val inputFile = File(inputPath)
    val outputFile = File(outputPath)

    if (!inputFile.exists()) {
        println("❌ Input file does not exist: $inputPath")
        return false
    }

    if (outputFile.exists() && !overwrite) {
        println("⚠️ Output file already exists and overwrite=false: $outputPath")
        return false
    }

    val cmd = listOf(
        "ffmpeg",
        "-y", // overwrite output
        "-i", inputPath,
        "-c", "copy",
        "-movflags", "+faststart",
        "-fflags", "+genpts",
        outputPath
    )

    return try {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            println("✅ Converted successfully: ${outputFile.absolutePath}")
            true
        } else {
            println("❌ FFmpeg failed with exit code $exitCode")
            println(output)
            false
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun writeH264FromStsd(
    outFile: File,
    stsd: StsdBox,
    idrFrameMp4: ByteArray
) {
    val spsList = mutableListOf<ByteArray>()
    val ppsList = mutableListOf<ByteArray>()

    val buf = ByteBuffer.wrap(stsd.avcC)
    buf.position(5)

    val numSps = buf.get().toInt() and 0x1F
    repeat(numSps) {
        val spsLen = buf.short.toInt() and 0xFFFF
        val sps = ByteArray(spsLen)
        buf.get(sps)
        spsList.add(sps)
    }

    val numPps = buf.get().toInt() and 0xFF
    repeat(numPps) {
        val ppsLen = buf.short.toInt() and 0xFFFF
        val pps = ByteArray(ppsLen)
        buf.get(pps)
        ppsList.add(pps)
    }

    FileOutputStream(outFile).use { out ->
        fun start() = out.write(byteArrayOf(0, 0, 0, 1))

        for (sps in spsList) {
            start()
            out.write(sps)
        }
        for (pps in ppsList) {
            start()
            out.write(pps)
        }

        var offset = 0
        while (offset + 4 <= idrFrameMp4.size) {
            val size = ByteBuffer.wrap(idrFrameMp4, offset, 4).int
            offset += 4
            start()
            out.write(idrFrameMp4, offset, size)
            offset += size
        }
    }
}


