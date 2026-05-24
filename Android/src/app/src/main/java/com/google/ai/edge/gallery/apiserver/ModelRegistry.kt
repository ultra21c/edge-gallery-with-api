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
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelRegistry"
private const val ALLOWLIST_FILENAME = "model_allowlist.json"

/**
 * Looks up which LLM models are downloaded by reading the cached `model_allowlist.json` and
 * checking the on-disk presence of each model file.
 *
 * Why bypass [CustomTask] / [ModelManagerViewModel]? The Gallery's `CustomTask` bindings are
 * Hilt multibindings whose member instances are unscoped. Each injection point (the UI's
 * `ModelManagerViewModel` vs. our singleton service) ends up with **different** `CustomTask`
 * instances, so the UI's `task.models` list is invisible to us. Reading the allowlist on disk
 * — which the UI also produces — gives us a stable, race-free view of registered models.
 *
 * Models are cached after the first read; UI changes (downloads / deletes) require an explicit
 * refresh, which the server can trigger on demand (e.g. when serving /v1/models).
 */
@Singleton
internal class ModelRegistry
@Inject
constructor(
  @ApplicationContext private val context: Context,
  // Still injected so the routes can pick a Task for inference initialization. Members may be
  // freshly-created instances; that's fine because we only read `task.id` from them.
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
) {

  @Volatile private var cached: List<Model>? = null

  /**
   * Picks the [CustomTask] best suited to drive inference for a given model.
   *
   * Preference order: audio (if model supports audio) → image → chat. Audio-capable Gemma 4
   * models must be initialized through the `LLM_ASK_AUDIO` task because that's what wires the
   * audio backend in [LlmChatModelHelper].
   */
  fun taskForModel(model: Model): CustomTask {
    val byId = customTasks.associateBy { it.task.id }
    if (model.llmSupportAudio) {
      byId[BuiltInTaskId.LLM_ASK_AUDIO]?.let { return it }
    }
    if (model.llmSupportImage) {
      byId[BuiltInTaskId.LLM_ASK_IMAGE]?.let { return it }
    }
    return byId[BuiltInTaskId.LLM_CHAT]
      ?: customTasks.firstOrNull { it.task.id.startsWith("llm_") }
      ?: customTasks.first()
  }

  /** Returns LLM models that have their model file on disk. */
  fun downloadedModels(): List<Model> = loadModels().filter { it.isLlm && isOnDisk(it) }

  /** Returns all LLM models declared in the allowlist (downloaded or not). */
  fun allLlmModels(): List<Model> = loadModels().filter { it.isLlm }

  /**
   * Forces a re-read of the allowlist from disk. Call after a download/delete completes if you
   * want the next [downloadedModels] call to reflect the change.
   */
  fun invalidate() {
    cached = null
  }

  fun resolve(requestedId: String?): Model? {
    val downloaded = downloadedModels()
    if (downloaded.isEmpty()) return null
    if (requestedId.isNullOrBlank()) return downloaded.first()
    val needle = normalize(requestedId)
    downloaded.firstOrNull { normalize(it.name) == needle }?.let { return it }
    downloaded.firstOrNull { normalize(it.name).contains(needle) }?.let { return it }
    return null
  }

  fun openAiId(model: Model): String = normalize(model.name)

  // ------------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------------

  private fun loadModels(): List<Model> {
    cached?.let { return it }
    val models = readAllowlistFromDisk()?.let { allowlist ->
      allowlist.models.mapNotNull { allowed ->
        if (allowed.disabled == true) return@mapNotNull null
        runCatching { allowed.toModel().also { it.preProcess() } }
          .onFailure { Log.w(TAG, "Failed to convert ${allowed.name}: ${it.message}") }
          .getOrNull()
      }
    } ?: emptyList()
    Log.i(TAG, "Loaded ${models.size} models from allowlist (LLM: ${models.count { it.isLlm }})")
    cached = models
    return models
  }

  private fun readAllowlistFromDisk(): ModelAllowlist? {
    val file = File(context.getExternalFilesDir(null), ALLOWLIST_FILENAME)
    if (!file.exists()) {
      Log.w(TAG, "Allowlist file not found at ${file.absolutePath}")
      return null
    }
    return try {
      val text = file.readText(Charsets.UTF_8)
      Gson().fromJson(text, ModelAllowlist::class.java)
    } catch (e: JsonSyntaxException) {
      Log.e(TAG, "Failed to parse allowlist", e)
      null
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read allowlist", e)
      null
    }
  }

  private fun isOnDisk(model: Model): Boolean {
    return try {
      val path = model.getPath(context)
      if (path.isBlank()) false else File(path).exists()
    } catch (_: Exception) {
      false
    }
  }

  private fun normalize(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-')
}
