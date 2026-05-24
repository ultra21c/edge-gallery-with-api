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

import com.google.ai.edge.gallery.proto.ApiServerConfig as ApiServerConfigProto

/**
 * Plain-Kotlin view of the API server configuration, decoupled from the generated proto type so
 * UI and route code never need to import the proto namespace directly.
 */
internal data class ApiServerConfig(
  val autoStart: Boolean,
  val port: Int,
  val apiKey: String,
  val idleUnloadMinutes: Int,
  val defaultModelName: String,
  val enableConversationCache: Boolean,
  val elevenLabsApiKey: String,
  val elevenLabsVoiceId: String,
  val elevenLabsModelId: String,
) {
  fun toProto(): ApiServerConfigProto =
    ApiServerConfigProto.newBuilder()
      .setAutoStart(autoStart)
      .setPort(port)
      .setApiKey(apiKey)
      .setIdleUnloadMinutes(idleUnloadMinutes)
      .setDefaultModelName(defaultModelName)
      .setEnableConversationCache(enableConversationCache)
      .setElevenlabsApiKey(elevenLabsApiKey)
      .setElevenlabsVoiceId(elevenLabsVoiceId)
      .setElevenlabsModelId(elevenLabsModelId)
      .build()

  /** True when the ElevenLabs backend is configured and should be tried first. */
  val elevenLabsEnabled: Boolean
    get() = elevenLabsApiKey.isNotBlank()

  companion object {
    const val DEFAULT_PORT = 11434
    const val DEFAULT_IDLE_MINUTES = 5
    const val DEFAULT_ELEVENLABS_MODEL = "eleven_multilingual_v2"

    fun fromProto(p: ApiServerConfigProto): ApiServerConfig =
      ApiServerConfig(
        autoStart = p.autoStart,
        port = if (p.port > 0) p.port else DEFAULT_PORT,
        apiKey = p.apiKey.orEmpty(),
        idleUnloadMinutes = if (p.idleUnloadMinutes > 0) p.idleUnloadMinutes else DEFAULT_IDLE_MINUTES,
        defaultModelName = p.defaultModelName.orEmpty(),
        enableConversationCache = p.enableConversationCache,
        elevenLabsApiKey = p.elevenlabsApiKey.orEmpty(),
        elevenLabsVoiceId = p.elevenlabsVoiceId.orEmpty(),
        elevenLabsModelId = p.elevenlabsModelId.orEmpty().ifBlank { DEFAULT_ELEVENLABS_MODEL },
      )

    val DEFAULT: ApiServerConfig =
      ApiServerConfig(
        autoStart = false,
        port = DEFAULT_PORT,
        apiKey = "",
        idleUnloadMinutes = DEFAULT_IDLE_MINUTES,
        defaultModelName = "",
        enableConversationCache = true,
        elevenLabsApiKey = "",
        elevenLabsVoiceId = "",
        elevenLabsModelId = DEFAULT_ELEVENLABS_MODEL,
      )
  }
}
