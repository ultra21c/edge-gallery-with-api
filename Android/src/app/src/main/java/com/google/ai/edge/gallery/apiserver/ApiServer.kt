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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json

private const val TAG = "ApiServer"

internal data class ApiServerHandle(val server: EmbeddedServer<*, *>, val port: Int) {
  fun stop() {
    runCatching { server.stop(gracePeriodMillis = 500L, timeoutMillis = 3_000L) }
  }
}

/**
 * Builds and starts the Ktor server. The server engine is CIO — Netty pulls in too much JVM-only
 * infrastructure for Android, while CIO is a pure-Kotlin/coroutines implementation that runs
 * well in the ART runtime.
 */
internal object ApiServer {

  val json: Json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      isLenient = true
      explicitNulls = false
    }

  fun start(deps: ServerDeps): ApiServerHandle {
    val port = deps.config.port
    Log.i(TAG, "Starting API server on 0.0.0.0:$port")
    val server =
      embeddedServer(CIO, host = "0.0.0.0", port = port) { configure(deps) }
        .also { it.start(wait = false) }
    return ApiServerHandle(server, port)
  }

  private fun Application.configure(deps: ServerDeps) {
    install(ContentNegotiation) { json(this@ApiServer.json) }
    install(CORS) {
      anyHost()
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Options)
      allowHeader(HttpHeaders.ContentType)
      allowHeader(HttpHeaders.Authorization)
      allowHeader("X-API-Key")
      allowHeader("X-Conversation-Id")
    }
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        Log.e(TAG, "Unhandled error on ${call.request.local.uri}", cause)
        val status =
          when (cause) {
            is InferenceTimeoutException -> HttpStatusCode.GatewayTimeout
            is InferenceOomException -> HttpStatusCode.ServiceUnavailable
            is InferenceInitException -> HttpStatusCode.ServiceUnavailable
            is IllegalArgumentException -> HttpStatusCode.BadRequest
            else -> HttpStatusCode.InternalServerError
          }
        call.respond(status, apiError(cause.message ?: "Unexpected error", code = "internal_error"))
      }
    }

    registerRoutes(deps)
  }
}

/** Bundle of singletons handed to the server when it boots. */
internal data class ServerDeps(
  val config: ApiServerConfig,
  val coordinator: InferenceCoordinator,
  val modelRegistry: ModelRegistry,
  val requestLog: RequestLog,
  val tts: TtsEngine,
  val startedAtMs: Long,
  val versionName: String,
)
