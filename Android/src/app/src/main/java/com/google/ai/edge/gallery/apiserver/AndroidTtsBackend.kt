/*
 * Copyright 2026 Google LLC
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
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AndroidTtsBackend"

/**
 * On-device TTS via Android's [TextToSpeech] system service. Acts as the always-available
 * fallback when ElevenLabs is unconfigured or unreachable.
 *
 * Single instance; per-request settings (voice / speed / pitch) are mutated under [synthMutex]
 * to keep concurrent callers from stomping each other's parameters.
 */
@Singleton
internal class AndroidTtsBackend
@Inject
constructor(@ApplicationContext private val context: Context) : TtsBackend {

  private val initLock = Any()
  @Volatile private var ttsRef: TextToSpeech? = null
  @Volatile private var ready: CompletableDeferred<TextToSpeech>? = null
  private val synthMutex = Mutex()

  override val displayName: String = "Android TTS"

  /** Lazily initializes the system [TextToSpeech]. */
  private suspend fun engine(): TextToSpeech {
    val existing = ready
    if (existing != null && existing.isCompleted) return existing.await()
    val deferred =
      synchronized(initLock) {
        ready?.let { return@synchronized it }
        val next = CompletableDeferred<TextToSpeech>()
        ready = next
        val instance =
          TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
              val t = ttsRef
              if (t != null) next.complete(t) else next.completeExceptionally(
                IllegalStateException("TTS init succeeded but engine ref is null")
              )
            } else {
              next.completeExceptionally(
                IllegalStateException("Android TTS init failed (status=$status)")
              )
            }
          }
        ttsRef = instance
        next
      }
    return deferred.await()
  }

  suspend fun listVoices(): List<Voice> = engine().voices.orEmpty().toList()

  override suspend fun synthesize(
    text: String,
    voiceSpec: TtsEngine.VoiceSpec,
    speed: Float,
    pitch: Float,
  ): TtsEngine.SynthesisResult {
    require(text.isNotBlank()) { "TTS input text is empty" }
    val tts = engine()
    return synthMutex.withLock {
      tts.setSpeechRate(speed.coerceIn(0.25f, 4.0f))
      tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
      applyVoice(tts, voiceSpec)

      val outFile = File.createTempFile("tts_", ".wav", context.cacheDir)
      val utteranceId = UUID.randomUUID().toString()
      val started = System.currentTimeMillis()
      try {
        suspendCancellableCoroutine<Unit> { cont ->
          tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(uid: String?) {}
              override fun onDone(uid: String?) {
                if (uid == utteranceId && !cont.isCompleted) cont.resume(Unit)
              }
              @Deprecated("Replaced by onError(String, Int) on newer SDKs")
              override fun onError(uid: String?) {
                if (uid == utteranceId && !cont.isCompleted) {
                  cont.resumeWithException(IllegalStateException("TTS synthesis failed (legacy)"))
                }
              }
              override fun onError(uid: String?, errorCode: Int) {
                if (uid == utteranceId && !cont.isCompleted) {
                  cont.resumeWithException(
                    IllegalStateException("TTS synthesis failed (code=$errorCode)")
                  )
                }
              }
            }
          )
          val rc = tts.synthesizeToFile(text, Bundle(), outFile, utteranceId)
          if (rc != TextToSpeech.SUCCESS && !cont.isCompleted) {
            cont.resumeWithException(
              IllegalStateException("synthesizeToFile() returned $rc")
            )
          }
          cont.invokeOnCancellation { runCatching { tts.stop() } }
        }

        val bytes = outFile.readBytes()
        val header = AudioFormats.parseWavHeader(bytes)
        TtsEngine.SynthesisResult(
          wavBytes = bytes,
          sampleRateHz = header?.sampleRate ?: 22050,
          channels = header?.channels ?: 1,
          bitsPerSample = header?.bitsPerSample ?: 16,
          durationMs = System.currentTimeMillis() - started,
          backend = displayName,
        )
      } finally {
        runCatching { outFile.delete() }
      }
    }
  }

  /** Best-effort voice picker; identical to the previous monolithic implementation. */
  private fun applyVoice(tts: TextToSpeech, spec: TtsEngine.VoiceSpec) {
    val voices = tts.voices.orEmpty()
    if (spec.voice != null) {
      voices.firstOrNull { it.name.equals(spec.voice, ignoreCase = true) }?.let { v ->
        Log.i(TAG, "Using exact voice match: ${v.name}")
        tts.voice = v
        return
      }
    }
    val byLocale =
      voices.filter { v ->
        v.locale.language == spec.locale.language &&
          (spec.locale.country.isEmpty() || v.locale.country == spec.locale.country)
      }
    if (byLocale.isNotEmpty()) {
      val offline = byLocale.filter { !it.isNetworkConnectionRequired }
      val candidates = if (offline.isNotEmpty()) offline else byLocale
      val byGender =
        when (spec.gender) {
          TtsEngine.Gender.FEMALE -> candidates.firstOrNull { isFemaleVoice(it) }
          TtsEngine.Gender.MALE -> candidates.firstOrNull { isMaleVoice(it) }
          TtsEngine.Gender.ANY -> candidates.firstOrNull { isFemaleVoice(it) } ?: candidates.firstOrNull()
        }
      val pick = byGender ?: candidates.first()
      Log.i(TAG, "Using locale voice: ${pick.name} (locale=${pick.locale})")
      tts.voice = pick
      return
    }
    val localeRc = tts.setLanguage(spec.locale)
    if (localeRc == TextToSpeech.LANG_MISSING_DATA || localeRc == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w(TAG, "Locale ${spec.locale} not available (rc=$localeRc); using engine default")
      tts.setLanguage(java.util.Locale.getDefault())
    }
  }

  private fun isFemaleVoice(v: Voice): Boolean {
    val name = v.name.lowercase()
    val features = v.features.orEmpty().joinToString(",").lowercase()
    return "female" in name || "female" in features ||
      name.contains("-f-") || name.contains("yuna")
  }

  private fun isMaleVoice(v: Voice): Boolean {
    val name = v.name.lowercase()
    val features = v.features.orEmpty().joinToString(",").lowercase()
    return "male" in name || "male" in features || name.contains("-m-")
  }

  fun shutdown() {
    runCatching {
      ttsRef?.stop()
      ttsRef?.shutdown()
    }
    ttsRef = null
    ready = null
  }
}
