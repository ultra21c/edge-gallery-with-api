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

import android.app.ActivityManager
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ApiServerUiState(
  val config: ApiServerConfig = ApiServerConfig.DEFAULT,
  val running: Boolean = false,
  val lanIp: String = "127.0.0.1",
)

/** State of a voice preview request triggered from the Settings UI. */
internal sealed interface VoicePreviewState {
  data object Idle : VoicePreviewState
  data object Synthesizing : VoicePreviewState
  data class Playing(val backend: String, val durationMs: Long) : VoicePreviewState
  data class Error(val message: String) : VoicePreviewState
}

/**
 * Drives the API Server section in Settings. Persists changes to DataStore and asks the
 * service to start/stop. The service itself reconciles port/api-key changes by restarting
 * Ktor; this VM only needs to write the new config.
 */
@HiltViewModel
internal class ApiServerViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val dataStoreRepository: DataStoreRepository,
  internal val requestLog: RequestLog,
  private val tts: TtsEngine,
) : ViewModel() {

  private val _running = MutableStateFlow(isServiceRunning())

  val uiState: StateFlow<ApiServerUiState> =
    combine(dataStoreRepository.apiServerConfigFlow(), _running) { proto, running ->
        ApiServerUiState(
          config = ApiServerConfig.fromProto(proto),
          running = running,
          lanIp = NetworkUtils.getLanIpAddress(),
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue =
          ApiServerUiState(
            config = ApiServerConfig.fromProto(dataStoreRepository.readApiServerConfig()),
            running = _running.value,
            lanIp = NetworkUtils.getLanIpAddress(),
          ),
      )

  fun setRunning(running: Boolean) {
    if (running) {
      ApiServerService.start(context)
    } else {
      ApiServerService.stop(context)
    }
    _running.update { running }
  }

  fun updateConfig(update: (ApiServerConfig) -> ApiServerConfig) {
    viewModelScope.launch {
      val current = ApiServerConfig.fromProto(dataStoreRepository.readApiServerConfig())
      dataStoreRepository.saveApiServerConfig(update(current).toProto())
    }
  }

  fun clearRequestLog() = requestLog.clear()

  // ------------------------------------------------------------------------
  // Voice preview
  // ------------------------------------------------------------------------

  private val _previewState = MutableStateFlow<VoicePreviewState>(VoicePreviewState.Idle)
  val previewState: StateFlow<VoicePreviewState> = _previewState
  @Volatile private var mediaPlayer: MediaPlayer? = null
  @Volatile private var previewFile: File? = null

  /**
   * Synthesizes a short Korean phrase with the currently-saved TTS config and plays it back
   * through the system audio. Lets the user audition voice IDs without leaving Settings.
   */
  fun previewVoice(text: String = DEFAULT_PREVIEW_TEXT) {
    viewModelScope.launch {
      stopPreviewInternal()
      _previewState.update { VoicePreviewState.Synthesizing }
      try {
        val voiceSpec =
          TtsEngine.VoiceSpec(
            voice = null, // backend resolves from saved config + OpenAI fallback
            locale = Locale.KOREA,
            gender = TtsEngine.Gender.ANY,
          )
        val result = tts.synthesize(text = text, voiceSpec = voiceSpec)
        val file = File(context.cacheDir, "tts_preview_${System.currentTimeMillis()}.wav")
        file.writeBytes(result.wavBytes)
        previewFile = file
        playFile(file, result.backend, result.durationMs)
      } catch (e: Exception) {
        Log.w("ApiServerVM", "Voice preview failed", e)
        _previewState.update { VoicePreviewState.Error(e.message ?: "Preview failed") }
      }
    }
  }

  fun stopPreview() = stopPreviewInternal()

  private fun stopPreviewInternal() {
    runCatching {
      mediaPlayer?.stop()
      mediaPlayer?.release()
    }
    mediaPlayer = null
    previewFile?.let { runCatching { it.delete() } }
    previewFile = null
    _previewState.update { VoicePreviewState.Idle }
  }

  private fun playFile(file: File, backend: String, durationMs: Long) {
    val player =
      MediaPlayer().apply {
        setDataSource(file.absolutePath)
        setOnCompletionListener {
          _previewState.update { VoicePreviewState.Idle }
          runCatching { release() }
          mediaPlayer = null
          runCatching { file.delete() }
          previewFile = null
        }
        setOnErrorListener { _, what, extra ->
          _previewState.update { VoicePreviewState.Error("Playback error ($what/$extra)") }
          runCatching { release() }
          mediaPlayer = null
          runCatching { file.delete() }
          previewFile = null
          true
        }
        prepare()
        start()
      }
    mediaPlayer = player
    _previewState.update { VoicePreviewState.Playing(backend, durationMs) }
  }

  override fun onCleared() {
    super.onCleared()
    stopPreviewInternal()
  }

  fun refreshRunningState() {
    _running.update { isServiceRunning() }
  }

  private companion object {
    const val DEFAULT_PREVIEW_TEXT =
      "안녕하세요. 음성 미리듣기입니다. 어떻게 들리시나요?"
  }

  @Suppress("DEPRECATION")
  private fun isServiceRunning(): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
    // getRunningServices is deprecated for general use, but is still permitted to query the
    // caller's own services — which is exactly our case.
    return am.getRunningServices(Int.MAX_VALUE).any {
      it.service.className == ApiServerService::class.java.name
    }
  }
}
