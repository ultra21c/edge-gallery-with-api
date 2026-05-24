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
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "ElevenLabsTtsBackend"
private const val BASE_URL = "https://api.elevenlabs.io"
private const val TIMEOUT_MS = 30_000L

/**
 * Cloud TTS via ElevenLabs. Used when the user has supplied an API key. Output is requested
 * as 24 kHz signed-LE PCM so the result lines up byte-for-byte with the Android backend's
 * native rate — no resampling required downstream.
 *
 * Voice id resolution (in order):
 *  1. [TtsEngine.VoiceSpec.voice] when it looks like a 20-char alphanumeric ElevenLabs id.
 *  2. OpenAI canonical names (alloy/echo/...) → curated default voice ids.
 *  3. Caller-configured default from `ApiServerConfig.elevenLabsVoiceId` (passed via [defaultVoiceId]).
 *  4. Final fallback: Rachel ("21m00Tcm4TlvDq8ikWAM").
 */
@Singleton
internal class ElevenLabsTtsBackend @Inject constructor() : TtsBackend {

  override val displayName: String = "ElevenLabs"

  private val client: HttpClient by lazy {
    HttpClient(Android) {
      install(HttpTimeout) {
        requestTimeoutMillis = TIMEOUT_MS
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = TIMEOUT_MS
      }
      expectSuccess = false
    }
  }

  /**
   * Synthesizes [text]. Caller must supply [apiKey] (validated upstream) and may supply
   * [defaultVoiceId]/[modelId] from server config. Throws [ElevenLabsException] on HTTP
   * failure so the router can fall back to the on-device backend transparently.
   */
  suspend fun synthesize(
    text: String,
    voiceSpec: TtsEngine.VoiceSpec,
    speed: Float,
    pitch: Float,
    apiKey: String,
    defaultVoiceId: String,
    modelId: String,
  ): TtsEngine.SynthesisResult {
    require(text.isNotBlank()) { "TTS input text is empty" }
    val voiceId = resolveVoiceId(voiceSpec, defaultVoiceId)
    val effectiveModel = modelId.ifBlank { ApiServerConfig.DEFAULT_ELEVENLABS_MODEL }

    val started = System.currentTimeMillis()
    val body =
      Json.encodeToString(
        TtsRequestBody.serializer(),
        TtsRequestBody(
          text = text,
          modelId = effectiveModel,
          voiceSettings = VoiceSettings(
            stability = 0.5f,
            similarityBoost = 0.75f,
            // ElevenLabs has its own "style" + "speed" — we map our speed but cap it because
            // the API only accepts a narrower range than Android TTS.
            style = 0.0f,
            speed = speed.coerceIn(0.7f, 1.2f),
          ),
        )
      )

    val response: HttpResponse =
      client.post("$BASE_URL/v1/text-to-speech/$voiceId") {
        // 24 kHz mono signed 16-bit LE — matches Android TTS native rate so callers don't
        // need to resample when swapping backends.
        parameter("output_format", "pcm_24000")
        header("xi-api-key", apiKey)
        header("Accept", "audio/pcm")
        contentType(ContentType.Application.Json)
        setBody(body)
      }

    if (response.status != HttpStatusCode.OK) {
      val errorText =
        try {
          response.bodyAsText().take(500)
        } catch (_: Exception) {
          "<no body>"
        }
      Log.w(TAG, "ElevenLabs HTTP ${response.status.value}: $errorText")
      throw ElevenLabsException(response.status.value, errorText)
    }

    val pcm = response.bodyAsBytes()
    if (pcm.isEmpty()) throw ElevenLabsException(0, "Empty PCM response")

    // ElevenLabs returns headerless PCM; wrap it so the rest of the pipeline (which assumes
    // WAV bytes) stays unchanged.
    val wav = AudioFormats.pcm16ToWav(pcm, sampleRate = 24000, channels = 1)
    return TtsEngine.SynthesisResult(
      wavBytes = wav,
      sampleRateHz = 24000,
      channels = 1,
      bitsPerSample = 16,
      durationMs = System.currentTimeMillis() - started,
      backend = displayName,
    )
  }

  // [TtsBackend.synthesize] requires apiKey/voice/model context that this class doesn't keep
  // locally; the router invokes the richer suspend above. We satisfy the interface by raising
  // here so accidental use surfaces immediately.
  override suspend fun synthesize(
    text: String,
    voiceSpec: TtsEngine.VoiceSpec,
    speed: Float,
    pitch: Float,
  ): TtsEngine.SynthesisResult {
    throw IllegalStateException(
      "ElevenLabsTtsBackend requires an API key — call the variant with credentials"
    )
  }

  private fun resolveVoiceId(spec: TtsEngine.VoiceSpec, defaultVoiceId: String): String {
    // (1) If voice looks like an ElevenLabs id (20 chars, alphanumeric), pass it through.
    spec.voice
      ?.takeIf { it.length == 20 && it.all { c -> c.isLetterOrDigit() } }
      ?.let { return it }
    // (2) OpenAI-canonical names.
    spec.voice?.lowercase()?.let { v ->
      ELEVENLABS_VOICE_MAP[v]?.let { return it }
    }
    // (3) Server default.
    if (defaultVoiceId.isNotBlank()) return defaultVoiceId
    // (4) Hardcoded fallback.
    return DEFAULT_VOICE_ID
  }

  @Serializable
  private data class TtsRequestBody(
    val text: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("voice_settings") val voiceSettings: VoiceSettings,
  )

  @Serializable
  private data class VoiceSettings(
    val stability: Float,
    @SerialName("similarity_boost") val similarityBoost: Float,
    val style: Float = 0f,
    val speed: Float = 1.0f,
  )

  companion object {
    /** Rachel — calm multilingual female; safe default for Korean. */
    const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

    /** OpenAI canonical voice → curated ElevenLabs voice id mapping. */
    val ELEVENLABS_VOICE_MAP: Map<String, String> =
      mapOf(
        "alloy" to "21m00Tcm4TlvDq8ikWAM",  // Rachel
        "nova" to "EXAVITQu4vr4xnSDxMaL",   // Bella
        "shimmer" to "MF3mGyEYCl7XYWbV9V6O", // Elli
        "fable" to "AZnzlk1XvdvUeBnXmlld",   // Domi
        "echo" to "VR6AewLTigWG4xSOukaG",    // Arnold
        "onyx" to "pNInz6obpgDQGcFmaJgB",    // Adam
      )
  }
}

internal class ElevenLabsException(val httpCode: Int, message: String) :
  RuntimeException("ElevenLabs ($httpCode): $message")
