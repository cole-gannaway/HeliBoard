// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceinput

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes 16-bit PCM audio data to [file] as a standard WAV file. The 44-byte
 * header is written with placeholder sizes up front and patched with the
 * final sizes once [close] is called, since the total data length is only
 * known after recording finishes.
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int = 16,
) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataSize = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))
    }

    fun write(buffer: ByteArray, length: Int) {
        raf.write(buffer, 0, length)
        dataSize += length
    }

    fun close() {
        writeHeader()
        raf.close()
    }

    private fun writeHeader() {
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // fmt chunk size
            putShort(1) // audio format: PCM
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }
        raf.seek(0)
        raf.write(header.array())
    }

    companion object {
        private const val HEADER_SIZE = 44
    }
}
