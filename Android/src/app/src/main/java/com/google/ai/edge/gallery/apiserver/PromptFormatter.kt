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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flattens an OpenAI-style `messages` array into a single prompt string suitable for feeding to
 * LiteRT LM's `sendMessageAsync`.
 *
 * Why a flat prompt? LiteRT LM's `Conversation` API maintains its own turn history once a
 * conversation is opened. When we reuse a cached conversation (via `conversation_id`), the
 * stored history already contains prior turns, so we only forward the new tail. When the
 * conversation was just reset (or no id was provided), we forward the full history but
 * format it with explicit role headers, which Gemma-style models reliably understand.
 *
 * Multimodal content parts are walked and:
 *  - `text` parts are concatenated into the prompt text
 *  - `audio_url`/`input_audio` parts are extracted into [audioClips] (caller decodes base64)
 */
internal object PromptFormatter {

  data class FormattedPrompt(val text: String, val systemInstruction: String?, val audio: List<String>)

  fun flatten(messages: List<ChatMessageDto>): FormattedPrompt {
    val builder = StringBuilder()
    var systemInstruction: String? = null
    val audioRefs = mutableListOf<String>()

    for ((index, msg) in messages.withIndex()) {
      val role = msg.role.lowercase()
      val (text, audios) = extractParts(msg.content)
      audioRefs.addAll(audios)

      if (role == "system") {
        // Only the first system message becomes the system instruction; subsequent system
        // messages are inlined so the prompt stays single-string.
        if (systemInstruction == null) {
          systemInstruction = text
          continue
        }
      }

      val isLast = index == messages.lastIndex
      val tag =
        when (role) {
          "user" -> "User"
          "assistant" -> "Assistant"
          "system" -> "System"
          "tool" -> "Tool"
          else -> role.replaceFirstChar { it.uppercase() }
        }
      if (text.isNotEmpty()) {
        if (builder.isNotEmpty()) builder.append("\n")
        builder.append(tag).append(": ").append(text)
      }
      if (isLast && role == "user") {
        // Hint the model to begin the assistant response.
        builder.append("\nAssistant:")
      }
    }

    return FormattedPrompt(
      text = builder.toString().trim(),
      systemInstruction = systemInstruction?.takeIf { it.isNotBlank() },
      audio = audioRefs,
    )
  }

  /**
   * Extracts plain text content and audio references from an OpenAI message `content` value.
   * The value may be a string, null, or an array of content parts.
   */
  private fun extractParts(content: JsonElement?): Pair<String, List<String>> {
    if (content == null) return "" to emptyList()
    return when (content) {
      is JsonPrimitive -> (content.contentOrNull.orEmpty()) to emptyList()
      is JsonArray -> {
        val texts = StringBuilder()
        val audios = mutableListOf<String>()
        for (part in content.jsonArray) {
          if (part !is JsonObject) continue
          val obj = part.jsonObject
          when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { texts.append(it) }
            "input_audio", "audio_url" -> {
              val audio = obj["input_audio"] ?: obj["audio_url"]
              if (audio is JsonObject) {
                (audio["data"] ?: audio["url"])?.jsonPrimitive?.contentOrNull?.let { audios.add(it) }
              }
            }
          }
        }
        texts.toString() to audios
      }
      else -> "" to emptyList()
    }
  }
}
