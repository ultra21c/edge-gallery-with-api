/*
 * Copyright 2025 Google LLC
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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// /v1/models
// ---------------------------------------------------------------------------

@Serializable
internal data class ModelListResponse(val `object`: String = "list", val data: List<ModelObject>)

@Serializable
internal data class ModelObject(
  val id: String,
  val `object`: String = "model",
  val created: Long,
  @SerialName("owned_by") val ownedBy: String = "edge-gallery",
  // Non-standard but useful metadata for clients.
  @SerialName("context_length") val contextLength: Int? = null,
  @SerialName("supports_image") val supportsImage: Boolean = false,
  @SerialName("supports_audio") val supportsAudio: Boolean = false,
  @SerialName("downloaded") val downloaded: Boolean = false,
)

// ---------------------------------------------------------------------------
// /v1/chat/completions  (request)
// ---------------------------------------------------------------------------

/**
 * Message content is either a plain string OR an array of content parts (multimodal). We accept
 * a [JsonElement] to support both shapes and decode lazily in the route handler.
 */
@Serializable
internal data class ChatMessageDto(
  val role: String,
  val content: JsonElement? = null,
  val name: String? = null,
)

@Serializable
internal data class ChatCompletionRequest(
  val model: String? = null,
  val messages: List<ChatMessageDto> = emptyList(),
  val temperature: Float? = null,
  @SerialName("top_p") val topP: Float? = null,
  @SerialName("top_k") val topK: Int? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  val stream: Boolean = false,
  val stop: JsonElement? = null,
  // Edge Gallery extension: opaque conversation id; identical id reuses KV cache.
  @SerialName("conversation_id") val conversationId: String? = null,
  // Edge Gallery extension: drop conversation cache after this turn.
  @SerialName("reset_after") val resetAfter: Boolean = false,
)

// ---------------------------------------------------------------------------
// /v1/chat/completions  (response — non-streaming)
// ---------------------------------------------------------------------------

@Serializable
internal data class ChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<Choice>,
  val usage: Usage,
)

@Serializable
internal data class Choice(
  val index: Int,
  val message: AssistantMessage,
  @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
internal data class AssistantMessage(val role: String = "assistant", val content: String)

@Serializable
internal data class Usage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
)

// ---------------------------------------------------------------------------
// /v1/chat/completions  (response — streaming chunks)
// ---------------------------------------------------------------------------

@Serializable
internal data class ChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoice>,
)

@Serializable
internal data class ChunkChoice(
  val index: Int,
  val delta: Delta,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class Delta(val role: String? = null, val content: String? = null)

// ---------------------------------------------------------------------------
// /v1/audio/transcriptions
// ---------------------------------------------------------------------------

@Serializable
internal data class TranscriptionResponse(
  val text: String,
  /** Optional ISO-639-1 language hint that was used. Non-OpenAI extension. */
  val language: String? = null,
  /** Duration of the input audio in seconds (when computable). Non-OpenAI extension. */
  val duration: Double? = null,
)

// ---------------------------------------------------------------------------
// /v1/audio/speech  (OpenAI-compatible Text-to-Speech)
// ---------------------------------------------------------------------------

@Serializable
internal data class SpeechRequest(
  val model: String? = null,
  /** Text to speak. Required. */
  val input: String = "",
  /** Voice id. Maps onto Android TTS voices; "alloy" etc. resolve to locale defaults. */
  val voice: String? = null,
  /** "wav", "pcm", "mp3", "opus", "flac", "aac". Only wav/pcm implemented locally. */
  @SerialName("response_format") val responseFormat: String? = null,
  /** 0.25–4.0; clamped server-side. */
  val speed: Float? = null,
  /** Non-OpenAI extension: 0.5–2.0 pitch multiplier. */
  val pitch: Float? = null,
  /**
   * Non-OpenAI extension: explicit BCP-47 language tag ("ko-KR", "en-US"). Overrides the
   * voice→locale inference.
   */
  val language: String? = null,
)

// ---------------------------------------------------------------------------
// /v1/voice/chat  (custom: STT + LLM + TTS in one request)
// ---------------------------------------------------------------------------

@Serializable
internal data class VoiceChatMeta(
  val transcript: String,
  @SerialName("response_text") val responseText: String,
  @SerialName("sample_rate") val sampleRate: Int,
  val channels: Int,
  @SerialName("bits_per_sample") val bitsPerSample: Int,
  /** Milliseconds spent per stage. Useful for ESP32 logging / debugging. */
  @SerialName("stt_ms") val sttMs: Long,
  @SerialName("llm_ms") val llmMs: Long,
  @SerialName("tts_ms") val ttsMs: Long,
)

// ---------------------------------------------------------------------------
// /health, /status, error responses
// ---------------------------------------------------------------------------

@Serializable
internal data class HealthResponse(
  val status: String = "ok",
  val version: String,
  val uptime_seconds: Long,
)

@Serializable
internal data class StatusResponse(
  val status: String,
  val active_model: String?,
  val cached_conversations: Int,
  val total_requests: Long,
  val failed_requests: Long,
  val last_request_ms_ago: Long?,
  val memory_used_mb: Long,
  val memory_max_mb: Long,
)

@Serializable
internal data class ApiError(val error: ErrorBody)

@Serializable
internal data class ErrorBody(
  val message: String,
  val type: String = "invalid_request_error",
  val code: String? = null,
)

internal fun apiError(message: String, type: String = "invalid_request_error", code: String? = null) =
  ApiError(ErrorBody(message, type, code))

internal fun jsonString(value: String) = JsonPrimitive(value)
