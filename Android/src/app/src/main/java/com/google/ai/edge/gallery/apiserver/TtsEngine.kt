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

import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TtsEngine"

/**
 * Top-level TTS façade used by the API routes. Routes [synthesize] requests to the best
 * available backend:
 *
 *   - **ElevenLabs** when [ApiServerConfig.elevenLabsApiKey] is set — high-quality, natural
 *     prosody, requires internet.
 *   - **Android TextToSpeech** as the always-available on-device fallback when ElevenLabs is
 *     unconfigured *or* fails transiently (no internet, quota exhausted, invalid key, etc.).
 *
 * Backend selection is recomputed on every request so flipping the API key in Settings takes
 * effect immediately without restarting the server.
 */
@Singleton
internal class TtsEngine
@Inject
constructor(
  private val androidBackend: AndroidTtsBackend,
  private val elevenLabsBackend: ElevenLabsTtsBackend,
  private val dataStoreRepository: DataStoreRepository,
) {

  /** Per-request voice description. */
  data class VoiceSpec(val voice: String?, val locale: Locale, val gender: Gender)
  enum class Gender { FEMALE, MALE, ANY }

  /** Result of a single synthesis. WAV bytes are 16-bit signed-LE PCM. */
  data class SynthesisResult(
    val wavBytes: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val durationMs: Long,
    /** Which backend actually produced this audio. Surfaced as `X-Tts-Backend` header. */
    val backend: String,
  )

  suspend fun synthesize(
    text: String,
    voiceSpec: VoiceSpec,
    speed: Float = 1.0f,
    pitch: Float = 1.0f,
  ): SynthesisResult {
    // LLMs (Gemma included) routinely emit markdown like `**서울**` even with prompts asking
    // for plain text. TTS engines don't auto-strip this, so they would voice "star star
    // 서울 star star". Normalize once at the engine boundary so every caller benefits.
    val speechText = TextNormalizer.forSpeech(text)
    if (speechText.isEmpty()) {
      throw IllegalArgumentException("TTS input is empty after markdown normalization")
    }

    val config = ApiServerConfig.fromProto(dataStoreRepository.readApiServerConfig())

    if (config.elevenLabsEnabled) {
      try {
        return elevenLabsBackend.synthesize(
          text = speechText,
          voiceSpec = voiceSpec,
          speed = speed,
          pitch = pitch,
          apiKey = config.elevenLabsApiKey,
          defaultVoiceId = config.elevenLabsVoiceId,
          modelId = config.elevenLabsModelId,
        )
      } catch (e: ElevenLabsException) {
        Log.w(TAG, "ElevenLabs failed (HTTP ${e.httpCode}); falling back to Android TTS")
      } catch (e: Exception) {
        Log.w(TAG, "ElevenLabs threw; falling back to Android TTS: ${e.message}")
      }
    }

    return androidBackend.synthesize(speechText, voiceSpec, speed, pitch)
  }

  /** Exposes the underlying voice list for the on-device backend (used by future UI). */
  suspend fun listAndroidVoices() = androidBackend.listVoices()

  fun shutdown() {
    androidBackend.shutdown()
  }
}
