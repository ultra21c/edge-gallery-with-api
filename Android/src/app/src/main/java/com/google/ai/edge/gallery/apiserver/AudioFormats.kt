/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.apiserver

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny RIFF/WAV parser + PCM extractor. Just enough to expose audio metadata to ESP32 clients
 * and to convert TTS output between WAV and raw PCM. We intentionally only handle PCM (format
 * code 1); anything else (ALAW/ULAW, IEEE float, encoded WAV) returns null so the caller can
 * fall back to handing the raw bytes through.
 */
internal object AudioFormats {

  /** Result of parsing a canonical RIFF/WAV header. */
  data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    /** Offset (in bytes from start of file) where the PCM `data` chunk begins. */
    val dataOffset: Int,
    /** Length of the PCM `data` chunk in bytes. */
    val dataLength: Int,
  )

  /**
   * Parses a WAV header. Returns null when the input is not a recognizable RIFF/WAVE/PCM file.
   * The parser walks chunks (`fmt `, `data`, plus any 'LIST'/'JUNK'/'bext'/etc.) until it
   * locates `data` — many encoders insert extra chunks before the audio body.
   */
  fun parseWavHeader(bytes: ByteArray): WavHeader? {
    if (bytes.size < 44) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.int != 0x46464952 /* "RIFF" */) return null
    /* val riffSize = */ buf.int
    if (buf.int != 0x45564157 /* "WAVE" */) return null

    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var formatCode = 0

    while (buf.remaining() >= 8) {
      val chunkId = buf.int
      val chunkSize = buf.int
      if (chunkSize < 0 || chunkSize > buf.remaining()) return null
      val chunkStart = buf.position()
      when (chunkId) {
        0x20746d66 /* "fmt " */ -> {
          if (chunkSize < 16) return null
          formatCode = buf.short.toInt() and 0xFFFF
          channels = buf.short.toInt() and 0xFFFF
          sampleRate = buf.int
          /* byteRate */ buf.int
          /* blockAlign */ buf.short
          bitsPerSample = buf.short.toInt() and 0xFFFF
          // Skip any extension bytes inside the fmt chunk.
          buf.position(chunkStart + chunkSize)
        }
        0x61746164 /* "data" */ -> {
          if (formatCode != 1 || sampleRate == 0) return null
          return WavHeader(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataOffset = chunkStart,
            dataLength = chunkSize,
          )
        }
        else -> {
          // Unknown chunk (LIST, bext, JUNK, ...). Skip; pad to even boundary.
          buf.position(chunkStart + chunkSize + (chunkSize and 1))
        }
      }
    }
    return null
  }

  /**
   * Returns the raw PCM body of a WAV file, suitable for piping into an ESP32 I2S DAC. Returns
   * null if the input is not a recognizable PCM WAV.
   */
  fun stripWavHeader(wav: ByteArray): ByteArray? {
    val h = parseWavHeader(wav) ?: return null
    if (h.dataOffset + h.dataLength > wav.size) return null
    val out = ByteArray(h.dataLength)
    System.arraycopy(wav, h.dataOffset, out, 0, h.dataLength)
    return out
  }

  /**
   * Wraps raw 16-bit signed little-endian PCM samples in a canonical 44-byte RIFF/WAVE header.
   * Useful when we synthesize PCM in code and want to hand back a self-describing WAV.
   */
  fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    val totalSize = 36 + pcm.size
    val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46464952)              // "RIFF"
    out.putInt(totalSize)
    out.putInt(0x45564157)              // "WAVE"
    out.putInt(0x20746d66)              // "fmt "
    out.putInt(16)                      // fmt chunk size (PCM)
    out.putShort(1)                     // PCM format
    out.putShort(channels.toShort())
    out.putInt(sampleRate)
    out.putInt(byteRate)
    out.putShort(blockAlign.toShort())
    out.putShort(16)                    // bits/sample
    out.putInt(0x61746164)              // "data"
    out.putInt(pcm.size)
    out.put(pcm)
    return out.array()
  }
}
