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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings section for the API server: toggle, port, IP, API key, idle timeout, log viewer.
 *
 * Drop this Composable into the existing scrollable column inside [SettingsDialog].
 */
@Composable
internal fun ApiServerSection(viewModel: ApiServerViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  var showLog by remember { mutableStateOf(false) }
  var portText by remember(state.config.port) { mutableStateOf(state.config.port.toString()) }
  var apiKeyText by remember(state.config.apiKey) { mutableStateOf(state.config.apiKey) }
  var elevenLabsKeyText by remember(state.config.elevenLabsApiKey) {
    mutableStateOf(state.config.elevenLabsApiKey)
  }
  var elevenLabsVoiceText by remember(state.config.elevenLabsVoiceId) {
    mutableStateOf(state.config.elevenLabsVoiceId)
  }

  LaunchedEffect(Unit) { viewModel.refreshRunningState() }

  Column(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "API Server",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = state.running,
        onCheckedChange = { viewModel.setRunning(it) },
      )
    }
    Text(
      "Exposes an OpenAI-compatible HTTP API on the LAN. Use from ESP32, scripts, etc.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Endpoint URL (tap to copy).
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      ) {
        val url = "http://${state.lanIp}:${state.config.port}"
        Text(
          url,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { copyToClipboard(context, "Edge Gallery API URL", url) }) {
          Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
        }
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = portText,
        onValueChange = { newValue ->
          portText = newValue.filter { it.isDigit() }.take(5)
          portText.toIntOrNull()?.let { p ->
            if (p in 1024..65535) viewModel.updateConfig { it.copy(port = p) }
          }
        },
        label = { Text("Port") },
        singleLine = true,
        keyboardOptions =
          androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      OutlinedTextField(
        value = apiKeyText,
        onValueChange = { newValue ->
          apiKeyText = newValue
          viewModel.updateConfig { it.copy(apiKey = newValue) }
        },
        label = { Text("API key (optional)") },
        singleLine = true,
        modifier = Modifier.weight(1.4f),
      )
    }

    Column {
      Text(
        "Auto-unload model after ${state.config.idleUnloadMinutes} min idle " +
          (if (state.config.idleUnloadMinutes == 0) "(never)" else ""),
        style = MaterialTheme.typography.bodyMedium,
      )
      Slider(
        value = state.config.idleUnloadMinutes.toFloat(),
        onValueChange = { v ->
          viewModel.updateConfig { it.copy(idleUnloadMinutes = v.toInt()) }
        },
        valueRange = 0f..30f,
        steps = 29,
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Reuse KV cache for repeated conversation_id",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = state.config.enableConversationCache,
        onCheckedChange = { v -> viewModel.updateConfig { it.copy(enableConversationCache = v) } },
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Auto-start on app launch",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = state.config.autoStart,
        onCheckedChange = { v -> viewModel.updateConfig { it.copy(autoStart = v) } },
      )
    }

    // ElevenLabs TTS configuration. Empty key falls back to Android TextToSpeech.
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
      "Voice synthesis (TTS)",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    val ttsStatus =
      if (state.config.elevenLabsApiKey.isNotBlank()) {
        "ElevenLabs active · cloud · natural voice"
      } else {
        "Android TTS (on-device) · add key below for natural voice"
      }
    Text(
      ttsStatus,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (state.config.elevenLabsApiKey.isNotBlank()) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = elevenLabsKeyText,
      onValueChange = { newValue ->
        elevenLabsKeyText = newValue
        viewModel.updateConfig { it.copy(elevenLabsApiKey = newValue.trim()) }
      },
      label = { Text("ElevenLabs API key") },
      placeholder = { Text("sk_…") },
      singleLine = true,
      visualTransformation =
        if (elevenLabsKeyText.isEmpty()) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = elevenLabsVoiceText,
      onValueChange = { newValue ->
        elevenLabsVoiceText = newValue
        viewModel.updateConfig { it.copy(elevenLabsVoiceId = newValue.trim()) }
      },
      label = { Text("ElevenLabs voice ID (optional)") },
      placeholder = { Text("e.g. 21m00Tcm4TlvDq8ikWAM (Rachel)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      "Tip: list voices at elevenlabs.io/app/voice-library — copy the Voice ID. " +
        "Leave blank to map OpenAI 'voice' names (alloy/nova/echo/…).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Voice preview — synthesizes "안녕하세요. 음성 미리듣기입니다." with current config and
    // plays through the phone speaker.
    val previewState by viewModel.previewState.collectAsState()
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(top = 4.dp),
    ) {
      Button(
        onClick = {
          if (previewState is VoicePreviewState.Playing) viewModel.stopPreview()
          else viewModel.previewVoice()
        },
        enabled = previewState !is VoicePreviewState.Synthesizing,
        modifier = Modifier.weight(1f),
      ) {
        when (val s = previewState) {
          is VoicePreviewState.Synthesizing -> {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Synthesizing…")
          }
          is VoicePreviewState.Playing -> {
            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Stop · ${s.backend}")
          }
          else -> {
            Icon(
              Icons.Outlined.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Preview voice")
          }
        }
      }
    }
    (previewState as? VoicePreviewState.Error)?.let { err ->
      Text(
        err.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
      OutlinedButton(onClick = { showLog = true }, modifier = Modifier.weight(1f)) {
        Text("View request log")
      }
      OutlinedButton(
        onClick = { openBatteryOptimizationSettings(context) },
        modifier = Modifier.weight(1f),
      ) {
        Text("Battery settings")
      }
    }
  }

  if (showLog) {
    RequestLogDialog(viewModel = viewModel, onDismiss = { showLog = false })
  }
}

@Composable
private fun RequestLogDialog(viewModel: ApiServerViewModel, onDismiss: () -> Unit) {
  val entries by viewModel.requestLog.entries.collectAsState()
  val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Recent requests (${entries.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
          )
          OutlinedButton(onClick = { viewModel.clearRequestLog() }) { Text("Clear") }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        if (entries.isEmpty()) {
          Text(
            "No requests yet. curl http://<ip>:<port>/health to test.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false),
          ) {
            items(
              items = entries,
              key = { it.id },
            ) { e ->
              Row {
                Text(
                  timeFmt.format(Date(e.timestampMs)),
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.width(72.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    "${e.method} ${e.path}",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  val statusColor =
                    if (e.statusCode in 200..299) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                  val detail = buildString {
                    append(e.statusCode)
                    append(" · ")
                    append(e.latencyMs).append("ms")
                    e.model?.let { append(" · ").append(it) }
                    e.completionTokens?.let { append(" · ").append(it).append(" tok") }
                    e.errorMessage?.let { append(" · ").append(it) }
                  }
                  Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                  )
                }
              }
            }
          }
        }
        OutlinedButton(
          onClick = onDismiss,
          modifier = Modifier.align(Alignment.End).padding(top = 12.dp),
        ) {
          Text("Close")
        }
      }
    }
  }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText(label, value))
  Toast.makeText(context, "Copied: $value", Toast.LENGTH_SHORT).show()
}

private fun openBatteryOptimizationSettings(context: Context) {
  try {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${context.packageName}")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  } catch (_: Exception) {
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(fallback)
  }
}
