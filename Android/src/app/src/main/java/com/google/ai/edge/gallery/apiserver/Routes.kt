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

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.util.Locale
import java.util.UUID
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "ApiRoutes"

internal fun Application.registerRoutes(deps: ServerDeps) {
  routing {
    // Ktor 3.x: the lambda receiver is RoutingContext, not ApplicationCall. We invoke our
    // ApplicationCall extension helpers through `call`.
    get("/health") { call.handleHealth(deps) }
    get("/status") { call.handleStatus(deps) }
    get("/v1/models") {
      if (!call.authorize(deps)) return@get
      call.handleListModels(deps)
    }
    post("/v1/chat/completions") {
      if (!call.authorize(deps)) return@post
      call.handleChatCompletions(deps)
    }
    post("/v1/audio/transcriptions") {
      if (!call.authorize(deps)) return@post
      call.handleAudioTranscription(deps)
    }
    post("/v1/audio/speech") {
      if (!call.authorize(deps)) return@post
      call.handleAudioSpeech(deps)
    }
    post("/v1/voice/chat") {
      if (!call.authorize(deps)) return@post
      call.handleVoiceChat(deps)
    }
  }
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.authorize(deps: ServerDeps): Boolean {
  val expected = deps.config.apiKey
  if (expected.isBlank()) return true
  val provided =
    request.headers["X-API-Key"]
      ?: request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
  if (provided == expected) return true
  respond(HttpStatusCode.Unauthorized, apiError("Invalid API key", code = "invalid_api_key"))
  return false
}

// ---------------------------------------------------------------------------
// /health
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleHealth(deps: ServerDeps) {
  val uptime = (System.currentTimeMillis() - deps.startedAtMs) / 1000
  respond(HealthResponse(version = deps.versionName, uptime_seconds = uptime))
}

// ---------------------------------------------------------------------------
// /status
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleStatus(deps: ServerDeps) {
  val runtime = Runtime.getRuntime()
  val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
  val max = runtime.maxMemory() / (1024 * 1024)
  val lastAt = deps.requestLog.lastRequestAtMs
  respond(
    StatusResponse(
      status = if (deps.coordinator.activeModelName() != null) "model_loaded" else "idle",
      active_model = deps.coordinator.activeModelName(),
      cached_conversations = if (deps.coordinator.activeModelName() != null) 1 else 0,
      total_requests = deps.requestLog.totalRequests,
      failed_requests = deps.requestLog.failedRequests,
      last_request_ms_ago = if (lastAt == 0L) null else System.currentTimeMillis() - lastAt,
      memory_used_mb = used,
      memory_max_mb = max,
    )
  )
}

// ---------------------------------------------------------------------------
// /v1/models
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleListModels(deps: ServerDeps) {
  val created = deps.startedAtMs / 1000
  val downloadedNames = deps.modelRegistry.downloadedModels().map { it.name }.toSet()
  val data =
    deps.modelRegistry.allLlmModels().map { model ->
      ModelObject(
        id = deps.modelRegistry.openAiId(model),
        created = created,
        contextLength = model.llmMaxToken.takeIf { it > 0 },
        supportsImage = model.llmSupportImage,
        supportsAudio = model.llmSupportAudio,
        downloaded = model.name in downloadedNames,
      )
    }
  respond(ModelListResponse(data = data))
}

// ---------------------------------------------------------------------------
// /v1/chat/completions
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleChatCompletions(deps: ServerDeps) {
  val started = System.currentTimeMillis()
  val req: ChatCompletionRequest =
    try {
      receive()
    } catch (e: Exception) {
      respond(HttpStatusCode.BadRequest, apiError("Malformed JSON: ${e.message}"))
      deps.requestLog.record(
        method = "POST",
        path = "/v1/chat/completions",
        model = null,
        statusCode = 400,
        latencyMs = System.currentTimeMillis() - started,
        errorMessage = e.message,
      )
      return
    }

  if (req.messages.isEmpty()) {
    respond(HttpStatusCode.BadRequest, apiError("`messages` must not be empty"))
    return
  }

  val model = deps.modelRegistry.resolve(req.model)
  if (model == null) {
    respond(
      HttpStatusCode.NotFound,
      apiError(
        "No downloaded model matches '${req.model}'. " +
          "Call GET /v1/models to see available ids.",
        code = "model_not_found",
      ),
    )
    deps.requestLog.record(
      method = "POST",
      path = "/v1/chat/completions",
      model = req.model,
      statusCode = 404,
      latencyMs = System.currentTimeMillis() - started,
      errorMessage = "model_not_found",
    )
    return
  }

  val prompt = PromptFormatter.flatten(req.messages)
  val convId = request.headers["X-Conversation-Id"] ?: req.conversationId
  val cacheEnabled = deps.config.enableConversationCache

  if (req.stream) {
    streamChatCompletions(
      deps,
      model,
      prompt,
      convId.takeIf { cacheEnabled },
      req.resetAfter,
      started,
    )
    return
  }

  try {
    val result =
      deps.coordinator.runBlocking(
        model = model,
        promptText = prompt.text,
        audioClips = emptyList(),
        conversationId = convId.takeIf { cacheEnabled },
        resetAfter = req.resetAfter,
      )
    val usage = result.usage.asUsage()
    val response =
      ChatCompletionResponse(
        id = "chatcmpl-${UUID.randomUUID()}",
        created = System.currentTimeMillis() / 1000,
        model = deps.modelRegistry.openAiId(model),
        choices =
          listOf(
            Choice(
              index = 0,
              message = AssistantMessage(content = result.text),
              finishReason = result.finishReason,
            )
          ),
        usage = usage,
      )
    respond(response)
    deps.requestLog.record(
      method = "POST",
      path = "/v1/chat/completions",
      model = model.name,
      statusCode = 200,
      latencyMs = System.currentTimeMillis() - started,
      promptTokens = usage.promptTokens,
      completionTokens = usage.completionTokens,
    )
  } catch (e: InferenceTimeoutException) {
    respond(HttpStatusCode.GatewayTimeout, apiError(e.message ?: "Inference timed out", code = "timeout"))
    deps.requestLog.record(
      "POST",
      "/v1/chat/completions",
      model.name,
      504,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  } catch (e: InferenceOomException) {
    respond(HttpStatusCode.ServiceUnavailable, apiError(e.message ?: "OOM", code = "oom"))
    deps.requestLog.record(
      "POST",
      "/v1/chat/completions",
      model.name,
      503,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  } catch (e: InferenceInitException) {
    respond(HttpStatusCode.ServiceUnavailable, apiError(e.message ?: "Init failed", code = "init_failed"))
    deps.requestLog.record(
      "POST",
      "/v1/chat/completions",
      model.name,
      503,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  }
}

/**
 * Streams chat completion chunks using the OpenAI canonical wire format:
 *
 * ```
 * data: {"id":..,"choices":[{"delta":{"role":"assistant"}}]}
 *
 * data: {"id":..,"choices":[{"delta":{"content":"Hello"}}]}
 *
 * data: {"id":..,"choices":[{"delta":{},"finish_reason":"stop"}]}
 *
 * data: [DONE]
 * ```
 */
private suspend fun ApplicationCall.streamChatCompletions(
  deps: ServerDeps,
  model: com.google.ai.edge.gallery.data.Model,
  prompt: PromptFormatter.FormattedPrompt,
  convId: String?,
  resetAfter: Boolean,
  started: Long,
) {
  val id = "chatcmpl-${UUID.randomUUID()}"
  val createdSec = System.currentTimeMillis() / 1000
  val openAiId = deps.modelRegistry.openAiId(model)

  val flow =
    deps.coordinator.runStreaming(
      model = model,
      promptText = prompt.text,
      conversationId = convId,
      resetAfter = resetAfter,
    )

  response.headers.append(HttpHeaders.CacheControl, "no-cache")
  response.headers.append(HttpHeaders.Connection, "keep-alive")
  response.headers.append("X-Accel-Buffering", "no")
  var completionChars = 0
  var errored = false
  var finishReason = "stop"

  respondTextWriter(ContentType.parse("text/event-stream")) {
    write(
      sseLine(
        ChatCompletionChunk(
          id = id,
          created = createdSec,
          model = openAiId,
          choices = listOf(ChunkChoice(0, Delta(role = "assistant"), finishReason = null)),
        )
      )
    )
    flush()
    flow.collect { event ->
      when (event) {
        is InferenceCoordinator.StreamEvent.Token -> {
          completionChars += event.text.length
          write(
            sseLine(
              ChatCompletionChunk(
                id = id,
                created = createdSec,
                model = openAiId,
                choices = listOf(ChunkChoice(0, Delta(content = event.text), finishReason = null)),
              )
            )
          )
          flush()
        }
        is InferenceCoordinator.StreamEvent.Done -> {
          finishReason = event.finishReason
          write(
            sseLine(
              ChatCompletionChunk(
                id = id,
                created = createdSec,
                model = openAiId,
                choices = listOf(ChunkChoice(0, Delta(), finishReason = finishReason)),
              )
            )
          )
          write("data: [DONE]\n\n")
          flush()
        }
        is InferenceCoordinator.StreamEvent.Error -> {
          errored = true
          write(sseLine(apiError(event.message, code = "stream_error")))
          write("data: [DONE]\n\n")
          flush()
        }
      }
    }
  }
  val usage = InferenceCoordinator.InferenceUsage(prompt.text.length, completionChars).asUsage()
  deps.requestLog.record(
    method = "POST",
    path = "/v1/chat/completions (stream)",
    model = model.name,
    statusCode = if (errored) 500 else 200,
    latencyMs = System.currentTimeMillis() - started,
    promptTokens = usage.promptTokens,
    completionTokens = usage.completionTokens,
    errorMessage = if (errored) "stream_error" else null,
  )
}

private fun sseLine(value: ChatCompletionChunk): String =
  "data: ${ApiServer.json.encodeToString(ChatCompletionChunk.serializer(), value)}\n\n"

private fun sseLine(value: ApiError): String =
  "data: ${ApiServer.json.encodeToString(ApiError.serializer(), value)}\n\n"

// ---------------------------------------------------------------------------
// /v1/audio/transcriptions
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleAudioTranscription(deps: ServerDeps) {
  val started = System.currentTimeMillis()
  val parts: MultiPartData =
    try {
      receiveMultipart()
    } catch (e: Exception) {
      respond(HttpStatusCode.BadRequest, apiError("Expected multipart/form-data: ${e.message}"))
      return
    }

  var audioBytes: ByteArray? = null
  var modelId: String? = null
  var language: String? = null
  var responseFormat: String? = null
  var prompt: String? = null
  parts.forEachPart { part ->
    when (part) {
      is PartData.FileItem -> {
        audioBytes = part.provider().readRemaining().readByteArray()
      }
      is PartData.FormItem -> {
        when (part.name) {
          "model" -> modelId = part.value
          "language" -> language = part.value
          "response_format" -> responseFormat = part.value
          "prompt" -> prompt = part.value
        }
      }
      else -> Unit
    }
    part.dispose()
  }

  val bytes = audioBytes
  if (bytes == null || bytes.isEmpty()) {
    respond(HttpStatusCode.BadRequest, apiError("Missing audio `file` part"))
    return
  }

  val model =
    deps.modelRegistry.resolve(modelId)
      ?: deps.modelRegistry.downloadedModels().firstOrNull { it.llmSupportAudio }
  if (model == null) {
    respond(
      HttpStatusCode.NotFound,
      apiError(
        "No downloaded audio-capable model is available. Download an audio-capable Gemma model first.",
        code = "model_not_found",
      ),
    )
    return
  }
  if (!model.llmSupportAudio) {
    respond(
      HttpStatusCode.BadRequest,
      apiError("Model '${model.name}' does not support audio input", code = "unsupported_model"),
    )
    return
  }

  // Compute a duration estimate from the WAV header (when present).
  val wavMeta = AudioFormats.parseWavHeader(bytes)
  val durationSec =
    wavMeta?.let { (it.dataLength.toDouble() / (it.sampleRate * it.channels * (it.bitsPerSample / 8))) }

  try {
    val result =
      deps.coordinator.runBlocking(
        model = model,
        promptText = buildTranscriptionPrompt(language, prompt),
        audioClips = listOf(bytes),
        conversationId = null,
        resetAfter = true,
      )
    val text = result.text.trim()
    deps.requestLog.record(
      "POST",
      "/v1/audio/transcriptions",
      model.name,
      200,
      System.currentTimeMillis() - started,
      completionTokens = result.usage.asUsage().completionTokens,
    )
    when (responseFormat?.lowercase()) {
      "text" -> respondText(text, ContentType.Text.Plain)
      "srt" -> respondText(buildSingleCueSrt(text, durationSec ?: 0.0), ContentType.Text.Plain)
      "vtt" -> respondText(buildSingleCueVtt(text, durationSec ?: 0.0), ContentType.Text.Plain)
      "verbose_json" ->
        respond(TranscriptionResponse(text = text, language = language, duration = durationSec))
      else -> respond(TranscriptionResponse(text = text, language = language, duration = durationSec))
    }
  } catch (e: Exception) {
    Log.e(TAG, "Transcription failed", e)
    respond(HttpStatusCode.InternalServerError, apiError(e.message ?: "Transcription failed"))
    deps.requestLog.record(
      "POST",
      "/v1/audio/transcriptions",
      model.name,
      500,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  }
}

private fun buildTranscriptionPrompt(language: String?, userPrompt: String?): String {
  val base = StringBuilder("Transcribe the provided audio. Output only the transcript text, nothing else.")
  when (language?.lowercase()?.take(2)) {
    "ko" -> base.append(" The speaker uses Korean; output Korean characters.")
    "en" -> base.append(" The speaker uses English.")
    "ja" -> base.append(" The speaker uses Japanese.")
    "zh" -> base.append(" The speaker uses Chinese.")
    null, "", "auto" -> { /* let the model detect */ }
    else -> base.append(" The speaker uses language code '$language'.")
  }
  if (!userPrompt.isNullOrBlank()) base.append(" Hint: ").append(userPrompt.trim())
  return base.toString()
}

private fun buildSingleCueSrt(text: String, durationSec: Double): String {
  val secs = if (durationSec > 0) durationSec else 5.0
  return "1\n00:00:00,000 --> ${formatSrt(secs)}\n$text\n"
}

private fun buildSingleCueVtt(text: String, durationSec: Double): String {
  val secs = if (durationSec > 0) durationSec else 5.0
  return "WEBVTT\n\n00:00:00.000 --> ${formatVtt(secs)}\n$text\n"
}

private fun formatSrt(secs: Double): String {
  val totalMs = (secs * 1000).toLong()
  val h = totalMs / 3_600_000
  val m = (totalMs / 60_000) % 60
  val s = (totalMs / 1000) % 60
  val ms = totalMs % 1000
  return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
}

private fun formatVtt(secs: Double): String {
  val totalMs = (secs * 1000).toLong()
  val h = totalMs / 3_600_000
  val m = (totalMs / 60_000) % 60
  val s = (totalMs / 1000) % 60
  val ms = totalMs % 1000
  return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
}

// ---------------------------------------------------------------------------
// /v1/audio/speech  (OpenAI TTS-compatible)
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleAudioSpeech(deps: ServerDeps) {
  val started = System.currentTimeMillis()
  val req: SpeechRequest =
    try {
      receive()
    } catch (e: Exception) {
      respond(HttpStatusCode.BadRequest, apiError("Malformed JSON: ${e.message}"))
      return
    }
  if (req.input.isBlank()) {
    respond(HttpStatusCode.BadRequest, apiError("`input` must not be empty"))
    return
  }
  val format = (req.responseFormat ?: "wav").lowercase()
  if (format !in setOf("wav", "pcm", "raw")) {
    // mp3/opus/flac/aac require an encoder we don't ship to keep the APK lean. Surface a
    // clear 501 so OpenAI clients fall back gracefully.
    respond(
      HttpStatusCode.NotImplemented,
      apiError(
        "response_format '$format' is not implemented on-device. Use 'wav' or 'pcm'.",
        code = "format_not_implemented",
      ),
    )
    return
  }

  val voiceSpec = resolveVoiceSpec(req.voice, req.language)
  try {
    val result =
      deps.tts.synthesize(
        text = req.input,
        voiceSpec = voiceSpec,
        speed = req.speed ?: 1.0f,
        pitch = req.pitch ?: 1.0f,
      )
    val (body, contentType) =
      when (format) {
        "pcm", "raw" -> {
          val pcm =
            AudioFormats.stripWavHeader(result.wavBytes)
              ?: return respond(
                HttpStatusCode.InternalServerError,
                apiError("TTS engine returned non-PCM WAV; cannot strip header"),
              )
          pcm to
            ContentType.parse("audio/L16; rate=${result.sampleRateHz}; channels=${result.channels}")
        }
        else -> result.wavBytes to ContentType.parse("audio/wav")
      }
    // Headers that ESP32 (or any embedded client) needs to configure I2S without re-parsing WAV.
    response.headers.append("X-Sample-Rate", result.sampleRateHz.toString())
    response.headers.append("X-Channels", result.channels.toString())
    response.headers.append("X-Bits-Per-Sample", result.bitsPerSample.toString())
    response.headers.append("X-Encoding", "pcm_s16le")
    response.headers.append("X-TTS-Ms", result.durationMs.toString())
    response.headers.append("X-Tts-Backend", result.backend)
    respondBytes(body, contentType)
    deps.requestLog.record(
      "POST",
      "/v1/audio/speech",
      voiceSpec.voice ?: voiceSpec.locale.toLanguageTag(),
      200,
      System.currentTimeMillis() - started,
    )
  } catch (e: Exception) {
    Log.e(TAG, "TTS failed", e)
    respond(HttpStatusCode.InternalServerError, apiError(e.message ?: "TTS failed"))
    deps.requestLog.record(
      "POST",
      "/v1/audio/speech",
      voiceSpec.voice,
      500,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  }
}

/**
 * Maps an OpenAI-style voice id onto a concrete [TtsEngine.VoiceSpec]. Recognized inputs:
 *
 *   - OpenAI canonical names (alloy / echo / fable / onyx / nova / shimmer) → locale + gender
 *     fallback. We treat the first two as Korean (matching the most common deployment), the
 *     rest as English; clients with strong opinions can pass `language` to override.
 *   - BCP-47 tags ("ko-KR", "en-US")
 *   - Compound tags like "ko-KR-female" / "en-US-male"
 *   - Verbatim Android voice names (e.g. "ko-kr-x-koc-network")
 */
private fun resolveVoiceSpec(voice: String?, languageOverride: String?): TtsEngine.VoiceSpec {
  val overrideLocale = languageOverride?.let { Locale.forLanguageTag(it) }
  val (locale, gender) =
    when (val v = voice?.lowercase()) {
      null, "", "default" -> (overrideLocale ?: Locale.KOREA) to TtsEngine.Gender.ANY
      "alloy", "nova" -> (overrideLocale ?: Locale.KOREA) to TtsEngine.Gender.FEMALE
      "echo", "onyx" -> (overrideLocale ?: Locale.KOREA) to TtsEngine.Gender.MALE
      "fable", "shimmer" -> (overrideLocale ?: Locale.US) to TtsEngine.Gender.FEMALE
      else -> {
        val gender =
          when {
            v.endsWith("-female") -> TtsEngine.Gender.FEMALE
            v.endsWith("-male") -> TtsEngine.Gender.MALE
            else -> TtsEngine.Gender.ANY
          }
        val tag = v.removeSuffix("-female").removeSuffix("-male")
        val parsed = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
        val ok = parsed != null && parsed.language.isNotEmpty()
        (if (ok) parsed!! else overrideLocale ?: Locale.KOREA) to gender
      }
    }
  // If the user passed a verbatim voice id, prefer it for the engine lookup.
  val verbatim = voice?.takeIf { it.contains('-') && !it.endsWith("-female") && !it.endsWith("-male") }
  return TtsEngine.VoiceSpec(voice = verbatim, locale = locale, gender = gender)
}

// ---------------------------------------------------------------------------
// /v1/voice/chat  (STT + LLM + TTS pipeline)
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleVoiceChat(deps: ServerDeps) {
  val started = System.currentTimeMillis()
  val parts: MultiPartData =
    try {
      receiveMultipart()
    } catch (e: Exception) {
      respond(HttpStatusCode.BadRequest, apiError("Expected multipart/form-data: ${e.message}"))
      return
    }

  var audioBytes: ByteArray? = null
  var modelId: String? = null
  var systemPrompt: String? = null
  var voice: String? = null
  var language: String? = null
  var responseFormat = "wav"
  var speed = 1.0f
  var pitch = 1.0f
  parts.forEachPart { part ->
    when (part) {
      is PartData.FileItem -> {
        audioBytes = part.provider().readRemaining().readByteArray()
      }
      is PartData.FormItem -> {
        when (part.name) {
          "model" -> modelId = part.value
          "system_prompt", "system" -> systemPrompt = part.value
          "voice" -> voice = part.value
          "language" -> language = part.value
          "response_format" -> responseFormat = part.value
          "speed" -> part.value.toFloatOrNull()?.let { speed = it }
          "pitch" -> part.value.toFloatOrNull()?.let { pitch = it }
        }
      }
      else -> Unit
    }
    part.dispose()
  }

  val bytes = audioBytes
  if (bytes == null || bytes.isEmpty()) {
    respond(HttpStatusCode.BadRequest, apiError("Missing audio `file` part"))
    return
  }
  val format = responseFormat.lowercase()
  if (format !in setOf("wav", "pcm", "raw", "json")) {
    respond(
      HttpStatusCode.NotImplemented,
      apiError("response_format '$format' not supported", code = "format_not_implemented"),
    )
    return
  }

  val model =
    deps.modelRegistry.resolve(modelId)
      ?: deps.modelRegistry.downloadedModels().firstOrNull { it.llmSupportAudio }
  if (model == null || !model.llmSupportAudio) {
    respond(
      HttpStatusCode.NotFound,
      apiError(
        "No downloaded audio-capable model is available.",
        code = "model_not_found",
      ),
    )
    return
  }

  val convId = request.headers["X-Conversation-Id"]
  val cacheEnabled = deps.config.enableConversationCache

  try {
    // -- 1. STT --
    val sttStart = System.currentTimeMillis()
    val transcript =
      deps.coordinator
        .runBlocking(
          model = model,
          promptText = buildTranscriptionPrompt(language, null),
          audioClips = listOf(bytes),
          conversationId = null,
          resetAfter = true,
        )
        .text.trim()
    val sttMs = System.currentTimeMillis() - sttStart

    if (transcript.isBlank()) {
      respond(HttpStatusCode.BadRequest, apiError("Transcription produced empty text"))
      return
    }

    // -- 2. LLM --
    val llmStart = System.currentTimeMillis()
    val messages = buildList {
      if (!systemPrompt.isNullOrBlank()) {
        add(ChatMessageDto(role = "system", content = JsonPrimitive(systemPrompt!!)))
      }
      add(ChatMessageDto(role = "user", content = JsonPrimitive(transcript)))
    }
    val prompt = PromptFormatter.flatten(messages)
    val llmResult =
      deps.coordinator.runBlocking(
        model = model,
        promptText = prompt.text,
        audioClips = emptyList(),
        conversationId = convId.takeIf { cacheEnabled },
        resetAfter = false,
      )
    val responseText = llmResult.text.trim()
    val llmMs = System.currentTimeMillis() - llmStart

    // -- 3. TTS --
    val ttsStart = System.currentTimeMillis()
    val voiceSpec = resolveVoiceSpec(voice, language)
    val tts = deps.tts.synthesize(responseText, voiceSpec, speed, pitch)
    val ttsMs = System.currentTimeMillis() - ttsStart

    val meta =
      VoiceChatMeta(
        transcript = transcript,
        responseText = responseText,
        sampleRate = tts.sampleRateHz,
        channels = tts.channels,
        bitsPerSample = tts.bitsPerSample,
        sttMs = sttMs,
        llmMs = llmMs,
        ttsMs = ttsMs,
      )

    response.headers.append("X-Transcript", encodeForHeader(transcript))
    response.headers.append("X-Response-Text", encodeForHeader(responseText))
    response.headers.append("X-Sample-Rate", tts.sampleRateHz.toString())
    response.headers.append("X-Channels", tts.channels.toString())
    response.headers.append("X-Bits-Per-Sample", tts.bitsPerSample.toString())
    response.headers.append("X-Encoding", "pcm_s16le")
    response.headers.append("X-Tts-Backend", tts.backend)
    response.headers.append("X-Stt-Ms", sttMs.toString())
    response.headers.append("X-Llm-Ms", llmMs.toString())
    response.headers.append("X-Tts-Ms", ttsMs.toString())

    when (format) {
      "json" -> respond(meta)
      "pcm", "raw" -> {
        val pcm =
          AudioFormats.stripWavHeader(tts.wavBytes)
            ?: return respond(
              HttpStatusCode.InternalServerError,
              apiError("TTS engine returned non-PCM WAV"),
            )
        respondBytes(
          pcm,
          ContentType.parse("audio/L16; rate=${tts.sampleRateHz}; channels=${tts.channels}"),
        )
      }
      else -> respondBytes(tts.wavBytes, ContentType.parse("audio/wav"))
    }

    deps.requestLog.record(
      "POST",
      "/v1/voice/chat",
      model.name,
      200,
      System.currentTimeMillis() - started,
      promptTokens = llmResult.usage.asUsage().promptTokens,
      completionTokens = llmResult.usage.asUsage().completionTokens,
    )
  } catch (e: Exception) {
    Log.e(TAG, "Voice chat failed", e)
    respond(HttpStatusCode.InternalServerError, apiError(e.message ?: "Voice chat failed"))
    deps.requestLog.record(
      "POST",
      "/v1/voice/chat",
      model.name,
      500,
      System.currentTimeMillis() - started,
      errorMessage = e.message,
    )
  }
}

/**
 * HTTP response headers (RFC 7230) only permit a narrow ASCII subset. Korean text contains
 * non-ASCII codepoints that would cause Ktor to throw, so we percent-encode every UTF-8 byte
 * outside the printable ASCII range. Clients can `decodeURIComponent()` to recover the text.
 */
private fun encodeForHeader(text: String): String {
  val sb = StringBuilder(text.length * 2)
  for (b in text.toByteArray(Charsets.UTF_8)) {
    val u = b.toInt() and 0xff
    if (u in 32..126 && u != 0x25 /* '%' */ && u != 0x22 /* '"' */ && u != 0x5c /* '\' */) {
      sb.append(u.toChar())
    } else {
      sb.append('%')
      sb.append(u.toString(16).padStart(2, '0').uppercase())
    }
  }
  return sb.toString()
}
