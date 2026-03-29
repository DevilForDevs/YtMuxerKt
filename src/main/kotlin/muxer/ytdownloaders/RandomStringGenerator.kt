package muxer.ytdownloaders

import java.security.SecureRandom

object RandomStringGenerator {
    private val numberGenerator = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun generateContentPlaybackNonce(): String {
        return generate(ALPHABET, 16)
    }

    fun generateTParameter(): String {
        return generate(ALPHABET, 12)
    }

    private fun generate(alphabet: String, length: Int): String {
        return (1..length)
            .map { alphabet[numberGenerator.nextInt(alphabet.length)] }
            .joinToString("")
    }
}