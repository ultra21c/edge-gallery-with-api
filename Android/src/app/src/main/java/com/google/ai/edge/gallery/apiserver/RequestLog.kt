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

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal data class RequestLogEntry(
  val id: Long,
  val timestampMs: Long,
  val method: String,
  val path: String,
  val model: String?,
  val statusCode: Int,
  val latencyMs: Long,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val errorMessage: String?,
)

/**
 * In-memory ring buffer of recent API requests. Backed by a [StateFlow] so UI can observe
 * updates without polling. Total / failed counters are atomic so they remain accurate under
 * concurrent writes.
 */
@Singleton
internal class RequestLog @Inject constructor() {

  private val nextId = AtomicLong(1)
  private val _totalRequests = AtomicLong(0)
  private val _failedRequests = AtomicLong(0)
  private val _lastRequestAtMs = AtomicLong(0)

  private val _entries = MutableStateFlow<List<RequestLogEntry>>(emptyList())
  val entries: StateFlow<List<RequestLogEntry>> = _entries

  val totalRequests: Long get() = _totalRequests.get()
  val failedRequests: Long get() = _failedRequests.get()
  val lastRequestAtMs: Long get() = _lastRequestAtMs.get()

  fun record(
    method: String,
    path: String,
    model: String?,
    statusCode: Int,
    latencyMs: Long,
    promptTokens: Int? = null,
    completionTokens: Int? = null,
    errorMessage: String? = null,
  ) {
    val now = System.currentTimeMillis()
    _totalRequests.incrementAndGet()
    if (statusCode >= 400) _failedRequests.incrementAndGet()
    _lastRequestAtMs.set(now)
    val entry =
      RequestLogEntry(
        id = nextId.getAndIncrement(),
        timestampMs = now,
        method = method,
        path = path,
        model = model,
        statusCode = statusCode,
        latencyMs = latencyMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        errorMessage = errorMessage,
      )
    _entries.update { current ->
      val next = ArrayList<RequestLogEntry>(minOf(current.size + 1, CAPACITY))
      next.add(entry)
      val take = minOf(current.size, CAPACITY - 1)
      for (i in 0 until take) next.add(current[i])
      next
    }
  }

  fun clear() {
    _entries.value = emptyList()
  }

  companion object {
    const val CAPACITY = 100
  }
}
