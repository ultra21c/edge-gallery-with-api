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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "ApiInferCoord"
private const val INFERENCE_TIMEOUT_MS = 60_000L

/**
 * Orchestrates LLM inference for the HTTP API.
 *
 * Responsibilities:
 *  - **Serial inference** — a single Mutex guarantees one inference runs at a time. Concurrent
 *    requests are queued.
 *  - **Lazy load** — the underlying engine is created on the first request that needs it.
 *  - **Idle unload** — after a configurable idle period the engine is closed, freeing native
 *    memory. The next request reloads.
 *  - **Conversation cache** — clients may pass an `X-Conversation-Id` (or `conversation_id`
 *    body field) to reuse KV cache across turns. Cached entries are evicted when a different
 *    conversation runs (single-conversation-per-engine constraint).
 *  - **OOM recovery** — if the engine throws an OOM during inference, it is torn down and
 *    rebuilt once before surfacing the error to the caller.
 */
@Singleton
internal class InferenceCoordinator
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val modelRegistry: ModelRegistry,
) {

  private val mutex = Mutex()

  // The model whose engine is currently loaded (null = nothing loaded).
  @Volatile private var loadedModel: Model? = null

  // Identifier of the conversation currently held by the engine (null = engine has the default
  // conversation with no caller-attached id).
  @Volatile private var activeConversationId: String? = null

  private val lastActivityAtMs = AtomicLong(0)

  // Idle watcher state.
  private var idleWatcherScope: CoroutineScope? = null
  private var idleWatcherJob: Job? = null
  @Volatile private var idleUnloadMinutes: Int = ApiServerConfig.DEFAULT_IDLE_MINUTES

  data class InferenceUsage(val promptChars: Int, val completionChars: Int) {
    // Char-based proxy for tokens. Real tokenizer counts are not exposed by LiteRT LM.
    fun asUsage(): Usage {
      val pt = (promptChars / 4).coerceAtLeast(if (promptChars > 0) 1 else 0)
      val ct = (completionChars / 4).coerceAtLeast(if (completionChars > 0) 1 else 0)
      return Usage(pt, ct, pt + ct)
    }
  }

  data class InferenceResult(val text: String, val usage: InferenceUsage, val finishReason: String)

  /** Encoded streaming event from the engine. */
  sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Done(val usage: InferenceUsage, val finishReason: String) : StreamEvent
    data class Error(val message: String) : StreamEvent
  }

  fun activeModelName(): String? = loadedModel?.name

  fun bind(scope: CoroutineScope, idleMinutes: Int) {
    idleUnloadMinutes = idleMinutes
    idleWatcherScope?.cancel()
    idleWatcherJob?.cancel()
    val newScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    idleWatcherScope = newScope
    if (idleMinutes > 0) {
      idleWatcherJob = newScope.launch { idleWatchLoop(idleMinutes) }
    }
  }

  fun shutdown() {
    idleWatcherJob?.cancel()
    idleWatcherScope?.cancel()
    idleWatcherScope = null
    idleWatcherJob = null
    runCatching { unloadInternal() }
  }

  /**
   * Runs a single, blocking inference. Coalesces the full result before returning.
   *
   * @param promptText raw prompt to feed to the engine (system + chat history already flattened
   *   by the caller).
   * @param audioClips optional audio inputs (PCM/WAV bytes) for multimodal models.
   */
  suspend fun runBlocking(
    model: Model,
    promptText: String,
    audioClips: List<ByteArray> = emptyList(),
    conversationId: String? = null,
    resetAfter: Boolean = false,
  ): InferenceResult =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        prepare(model, conversationId)
        val collected = StringBuilder()
        var finishReason = "stop"
        try {
          withTimeout(INFERENCE_TIMEOUT_MS) {
            runInferenceAwait(model, promptText, audioClips) { token -> collected.append(token) }
          }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
          stopEngine(model)
          // The async callback may still complete after cancelProcess. Force a conversation
          // reset so the next request starts from a known state.
          runCatching { resetConversation(model); activeConversationId = null }
          finishReason = "length"
          throw InferenceTimeoutException("Inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
        } catch (e: OutOfMemoryError) {
          handleOom(model)
          throw InferenceOomException("Out of memory; engine was reset")
        }
        lastActivityAtMs.set(System.currentTimeMillis())
        if (resetAfter) dropActiveConversation()
        InferenceResult(
          text = collected.toString(),
          usage = InferenceUsage(promptText.length, collected.length),
          finishReason = finishReason,
        )
      }
    }

  /**
   * Streams tokens for an inference. Emits [StreamEvent.Token] for each chunk and exactly one
   * terminal [StreamEvent.Done] or [StreamEvent.Error]. The mutex is held for the whole stream.
   */
  fun runStreaming(
    model: Model,
    promptText: String,
    audioClips: List<ByteArray> = emptyList(),
    conversationId: String? = null,
    resetAfter: Boolean = false,
  ): Flow<StreamEvent> = channelFlow {
    mutex.withLock {
      var completionChars = 0
      try {
        prepare(model, conversationId)
        withTimeout(INFERENCE_TIMEOUT_MS) {
          runInferenceAwait(model, promptText, audioClips) { token ->
            completionChars += token.length
            trySend(StreamEvent.Token(token))
          }
        }
        send(
          StreamEvent.Done(
            InferenceUsage(promptText.length, completionChars),
            finishReason = "stop",
          )
        )
      } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        stopEngine(model)
        runCatching { resetConversation(model); activeConversationId = null }
        send(
          StreamEvent.Done(
            InferenceUsage(promptText.length, completionChars),
            finishReason = "length",
          )
        )
      } catch (e: OutOfMemoryError) {
        handleOom(model)
        send(StreamEvent.Error("Out of memory; engine was reset"))
      } catch (e: Exception) {
        send(StreamEvent.Error(e.message ?: "Inference failed"))
      } finally {
        lastActivityAtMs.set(System.currentTimeMillis())
        if (resetAfter) dropActiveConversation()
      }
    }
  }

  /** Forces the engine to unload. Safe to call when nothing is loaded. */
  suspend fun unload() = mutex.withLock { unloadInternal() }

  // ------------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------------

  private suspend fun prepare(model: Model, conversationId: String?) {
    val currentlyLoaded = loadedModel
    val needsSwap = currentlyLoaded == null || currentlyLoaded.name != model.name
    if (needsSwap) {
      currentlyLoaded?.let { closeModel(it) }
      initializeModel(model)
      loadedModel = model
      activeConversationId = conversationId
    } else if (conversationId != activeConversationId) {
      // Same engine but different conversation — reset KV cache.
      resetConversation(model)
      activeConversationId = conversationId
    }
  }

  private suspend fun initializeModel(model: Model) {
    val task = modelRegistry.taskForModel(model).task
    val supportImage = model.llmSupportImage
    val supportAudio = model.llmSupportAudio
    Log.i(TAG, "Initializing model '${model.name}' via task '${task.id}'")
    suspendCancellableCoroutine<Unit> { cont ->
      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = supportImage,
        supportAudio = supportAudio,
        onDone = { err ->
          if (err.isEmpty()) {
            cont.resume(Unit)
          } else {
            cont.resumeWithException(InferenceInitException(err))
          }
        },
        systemInstruction = null,
        tools = emptyList(),
        enableConversationConstrainedDecoding = false,
        coroutineScope = null,
      )
    }
  }

  private fun resetConversation(model: Model) {
    LlmChatModelHelper.resetConversation(
      model = model,
      supportImage = model.llmSupportImage,
      supportAudio = model.llmSupportAudio,
      systemInstruction = null,
      tools = emptyList(),
      enableConversationConstrainedDecoding = false,
      initialMessages = emptyList(),
    )
  }

  private fun dropActiveConversation() {
    val m = loadedModel ?: return
    runCatching { resetConversation(m) }
    activeConversationId = null
  }

  private suspend fun runInferenceAwait(
    model: Model,
    promptText: String,
    audioClips: List<ByteArray>,
    onToken: (String) -> Unit,
  ) {
    val instance = model.instance as? LlmModelInstance
      ?: throw InferenceInitException("Engine not initialized")

    val contents = buildList {
      audioClips.forEach { add(Content.AudioBytes(it)) }
      if (promptText.isNotEmpty()) add(Content.Text(promptText))
    }

    val terminal = Channel<Throwable?>(capacity = 1)
    instance.conversation.sendMessageAsync(
      Contents.of(contents),
      object : com.google.ai.edge.litertlm.MessageCallback {
        override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
          val text = message.toString()
          // The engine emits engine-internal control tokens (e.g. "<ctrl101>") that should never
          // reach API clients — the chat UI silently drops them too.
          if (text.isEmpty() || text.startsWith("<ctrl")) return
          onToken(text)
        }
        override fun onDone() {
          terminal.trySend(null)
        }
        override fun onError(throwable: Throwable) {
          terminal.trySend(throwable)
        }
      },
      emptyMap(),
    )
    val err = terminal.receive()
    if (err != null) throw err
  }

  private fun stopEngine(model: Model) {
    runCatching { LlmChatModelHelper.stopResponse(model) }
  }

  private suspend fun handleOom(model: Model) {
    Log.w(TAG, "OOM during inference; resetting engine for '${model.name}'")
    closeModel(model)
    loadedModel = null
    activeConversationId = null
    // Try to reload once so the next request succeeds.
    runCatching { initializeModel(model); loadedModel = model }
  }

  private fun closeModel(model: Model) {
    runCatching {
      LlmChatModelHelper.cleanUp(model) { /* no-op */ }
    }
  }

  private fun unloadInternal() {
    val m = loadedModel ?: return
    closeModel(m)
    loadedModel = null
    activeConversationId = null
    Log.i(TAG, "Engine unloaded.")
  }

  private suspend fun idleWatchLoop(idleMinutes: Int) {
    val idleMs = idleMinutes * 60_000L
    while (true) {
      delay(30_000L)
      val last = lastActivityAtMs.get()
      if (last == 0L) continue
      if (loadedModel == null) continue
      val sinceLast = System.currentTimeMillis() - last
      if (sinceLast >= idleMs) {
        // Try-lock so we never block an in-flight request.
        if (mutex.tryLock()) {
          try {
            unloadInternal()
          } finally {
            mutex.unlock()
          }
        }
      }
    }
  }
}

internal class InferenceInitException(message: String) : Exception(message)

internal class InferenceTimeoutException(message: String) : Exception(message)

internal class InferenceOomException(message: String) : Exception(message)
