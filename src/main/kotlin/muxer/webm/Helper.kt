package muxer.webm

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class Helper {
    fun hexToBytes(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    fun encodeVInt8(value: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        for (i in 0 until length) {
            buffer[length - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        buffer[0] = (buffer[0].toInt() or (1 shl (8 - length))).toByte() // Set VINT marker bit
        return buffer
    }
    fun muxerInfoBytes(duration: Double): ByteArray{
        val info = ByteArrayOutputStream()
        info.write(ebmlUInt(0x2AD7B1,  1000000)) // TimecodeScale = 1ms
        info.write(ebmlFloat(0x4489, duration)) // Duration = 10.0s (example)
        info.write(ebmlString(0x4D80, "ChatGPTMuxer"))
        info.write(ebmlString(0x5741, "KotlinMuxer"))
        return info.toByteArray()

    }

    private fun ebmlUInt(id: Long, value: Long): ByteArray {
        val data = ByteBuffer.allocate(8).putLong(value).array().dropWhile { it == 0.toByte() }.toByteArray()
        return writeElement(id, data)
    }

    private fun ebmlFloat(id: Long, value: Double): ByteArray {
        val buf = ByteBuffer.allocate(8).putDouble(value).array()
        return writeElement(id, buf)
    }

    private fun ebmlString(id: Long, value: String): ByteArray {
        return writeElement(id, value.toByteArray(Charsets.UTF_8))
    }

    fun writeElement(id: Long, data: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        val sizeBytes = encodeVInt(data.size.toLong())
        return idBytes + sizeBytes + data
    }

    fun idToBytes(id: Long): ByteArray {
        val bytes = ByteBuffer.allocate(8).putLong(id).array()
        return bytes.dropWhile { it == 0.toByte() }.toByteArray()
    }

    fun trakBytes(sources: List<WebMParser>): ByteArrayOutputStream {
        val tracks = ByteArrayOutputStream()
        var trackCounter = 1L // sequential track number

        sources.forEach { src ->
            src.tracks.forEach { trackInfo ->
                val entry = ByteArrayOutputStream()

                // --- TrackNumber depends on type ---
                entry.write(ebmlUInt(0xD7, trackCounter))
                trackCounter++ // increment for next track

                // TrackUID
                entry.write(ebmlUInt(0x73C5, (1000 + trackCounter)))

                // TrackType: 1 = video, 2 = audio
                entry.write(ebmlUInt(0x83, trackInfo.type))

                // Codec info
                entry.write(ebmlString(0x86, trackInfo.codecID))
                if (trackInfo.codecName.isNotEmpty()) {
                    entry.write(ebmlString(0x258688, trackInfo.codecName))
                }

                // Language
                entry.write(ebmlString(0x22B59C, trackInfo.language))

                // CodecPrivate
                if (trackInfo.codecPrivate.isNotEmpty()) {
                    entry.write(writeElement(0x63A2, trackInfo.codecPrivate))
                }

                // --- Video fields only for video tracks ---
                if (trackInfo.type == 1L) {
                    val video = ByteArrayOutputStream()
                    if (trackInfo.width > 0) video.write(ebmlUInt(0xB0, trackInfo.width))
                    if (trackInfo.height > 0) video.write(ebmlUInt(0xBA, trackInfo.height))
                    entry.write(writeMasterElementBytes(0xE0, video.toByteArray()))
                }

                // --- Audio fields only for audio tracks ---
                if (trackInfo.type == 2L) {
                    val audio = ByteArrayOutputStream()
                    if (trackInfo.samplingFrequency > 0) audio.write(ebmlFloat(0xB5, trackInfo.samplingFrequency))
                    if (trackInfo.channels > 0) audio.write(ebmlUInt(0x9F, trackInfo.channels))
                    entry.write(writeMasterElementBytes(0xE1, audio.toByteArray()))
                }

                // --- Write TrackEntry ---
                tracks.write(writeMasterElementBytes(0xAE, entry.toByteArray()))
            }
        }

        return tracks
    }

    fun writeMasterElementBytes(id: Long, data: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        val sizeBytes = encodeVInt(data.size.toLong())
        return idBytes + sizeBytes + data
    }

    fun encodeTimecode(timecode: Int): ByteArray {
        // Convert int to minimum bytes needed
        val bytes = mutableListOf<Byte>()
        var value = timecode
        var shift = 24

        // Determine number of bytes needed (non-zero MSB)
        var started = false
        for (i in 3 downTo 0) {
            val b = ((value shr (i * 8)) and 0xFF).toByte()
            if (b.toInt() != 0 || started) {
                bytes.add(b)
                started = true
            }
        }

        // If timecode is 0, at least 1 byte is required
        if (bytes.isEmpty()) bytes.add(0x00)

        return bytes.toByteArray()
    }

    fun writeSimpleBlock(block: BlockEntry, track: Int, blockTimeCode: Long): ByteArray {
        // --- Read raw VP9 frame ---
        val rawFrame = ByteArray(block.frameSize.toInt())
        block.rf.seek(block.frameAbsoluteOffset)
        block.rf.readFully(rawFrame)

        // --- Track number as VInt ---
        val trackVInt = encodeVInt(track.toLong())

        // --- Relative timecode ---
        val relativeTimecode = (blockTimeCode).toShort()
        val timecode = ByteBuffer.allocate(2).putShort(relativeTimecode).array()

        // --- Flags ---
        val flags = if (block.isKeyframe) 0x80.toByte() else 0x00.toByte()

        // --- Build SimpleBlock data ---
        val blockData = trackVInt + timecode + byteArrayOf(flags) + rawFrame

       return blockData
    }





}