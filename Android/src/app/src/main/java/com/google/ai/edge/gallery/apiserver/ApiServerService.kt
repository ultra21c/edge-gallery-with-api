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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "ApiServerService"
private const val CHANNEL_ID = "api_server_channel"
private const val NOTIFICATION_ID = 0xA5E2

internal const val ACTION_START_API_SERVER = "com.google.ai.edge.gallery.apiserver.START"
internal const val ACTION_STOP_API_SERVER = "com.google.ai.edge.gallery.apiserver.STOP"

/**
 * Foreground service that hosts the Ktor API server.
 *
 * Lifecycle:
 *  1. [onStartCommand] receives an intent with [ACTION_START_API_SERVER] (typically from
 *     Settings). The service starts a foreground notification and boots the Ktor engine.
 *  2. While running, it observes [DataStoreRepository.apiServerConfigFlow] — port changes
 *     trigger a restart, other field changes flow through to [InferenceCoordinator] without
 *     a restart.
 *  3. [ACTION_STOP_API_SERVER] (notification action or Settings toggle) cleanly stops Ktor,
 *     releases wake/WiFi locks, and calls [stopSelf].
 *
 * Acquires:
 *  - `PARTIAL_WAKE_LOCK` so the CPU stays alive while the engine is loaded.
 *  - `WifiManager.WIFI_MODE_FULL_HIGH_PERF` so WiFi stays on with full bandwidth even under
 *    Doze. This is essential — without it, sleeping the phone breaks the LAN connection.
 */
@AndroidEntryPoint
class ApiServerService : Service() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject internal lateinit var coordinator: InferenceCoordinator
  @Inject internal lateinit var modelRegistry: ModelRegistry
  @Inject internal lateinit var requestLog: RequestLog
  @Inject internal lateinit var tts: TtsEngine

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var configWatcher: Job? = null

  @Volatile private var handle: ApiServerHandle? = null
  @Volatile private var currentConfig: ApiServerConfig = ApiServerConfig.DEFAULT
  @Volatile private var startedAtMs: Long = 0L

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_API_SERVER -> {
        stopServer()
        stopSelf()
        return START_NOT_STICKY
      }
    }

    if (handle != null) {
      // Already running; just refresh foreground notification (e.g. after a notification tap).
      updateForegroundNotification()
      return START_STICKY
    }

    val config = loadConfigFromDisk()
    startServer(config)
    observeConfig()
    return START_STICKY
  }

  override fun onDestroy() {
    stopServer()
    scope.coroutineContext[Job]?.cancel()
    super.onDestroy()
  }

  // ------------------------------------------------------------------------
  // Server lifecycle
  // ------------------------------------------------------------------------

  private fun loadConfigFromDisk(): ApiServerConfig =
    ApiServerConfig.fromProto(dataStoreRepository.readApiServerConfig())

  private fun startServer(config: ApiServerConfig) {
    currentConfig = config
    startedAtMs = System.currentTimeMillis()
    acquireLocks()
    startForegroundCompat(buildNotification(config.port, "Starting…"))
    coordinator.bind(scope, config.idleUnloadMinutes)
    val deps =
      ServerDeps(
        config = config,
        coordinator = coordinator,
        modelRegistry = modelRegistry,
        requestLog = requestLog,
        tts = tts,
        startedAtMs = startedAtMs,
        versionName = appVersionName(),
      )
    handle =
      try {
        ApiServer.start(deps)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start API server", e)
        releaseLocks()
        stopSelf()
        return
      }
    updateForegroundNotification()
    Log.i(TAG, "API server started on port ${config.port}")
  }

  private fun stopServer() {
    handle?.stop()
    handle = null
    runCatching { coordinator.shutdown() }
    releaseLocks()
    configWatcher?.cancel()
    configWatcher = null
  }

  private fun observeConfig() {
    configWatcher?.cancel()
    configWatcher =
      scope.launch {
        dataStoreRepository.apiServerConfigFlow().collectLatest { proto ->
          val next = ApiServerConfig.fromProto(proto)
          val portChanged = next.port != currentConfig.port
          val apiKeyChanged = next.apiKey != currentConfig.apiKey
          val idleChanged = next.idleUnloadMinutes != currentConfig.idleUnloadMinutes
          currentConfig = next
          if (idleChanged) coordinator.bind(scope, next.idleUnloadMinutes)
          if (portChanged || apiKeyChanged) {
            handle?.stop()
            handle = null
            startServer(next)
          } else {
            updateForegroundNotification()
          }
        }
      }
  }

  // ------------------------------------------------------------------------
  // Locks
  // ------------------------------------------------------------------------

  private fun acquireLocks() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EdgeGallery:ApiServer").apply {
        setReferenceCounted(false)
        acquire()
      }
    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    wifiLock =
      wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EdgeGallery:ApiServer").apply {
        setReferenceCounted(false)
        acquire()
      }
  }

  private fun releaseLocks() {
    runCatching {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    runCatching {
      if (wifiLock?.isHeld == true) wifiLock?.release()
    }
    wakeLock = null
    wifiLock = null
  }

  // ------------------------------------------------------------------------
  // Notification
  // ------------------------------------------------------------------------

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Edge Gallery API Server",
        NotificationManager.IMPORTANCE_LOW,
      )
        .apply {
          description = "Notifies that the local LLM API server is running"
          setShowBadge(false)
        }
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(channel)
  }

  private fun buildNotification(port: Int, statusLine: String): Notification {
    val ip = NetworkUtils.getLanIpAddress()
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent =
      PendingIntent.getBroadcast(
        this,
        1,
        Intent(this, ApiServerNotificationReceiver::class.java).apply { action = ACTION_STOP_API_SERVER },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Gemma API Server Running")
      .setContentText("http://$ip:$port  ·  $statusLine")
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(
            "Listening on http://$ip:$port\n$statusLine\n\nTap to open Edge Gallery; use Stop to shut down."
          )
      )
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openIntent)
      .addAction(0, "Stop", stopIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateForegroundNotification() {
    val active = coordinator.activeModelName()
    val statusLine = if (active != null) "Active: $active" else "Idle (model not loaded)"
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, buildNotification(currentConfig.port, statusLine))
  }

  private fun appVersionName(): String =
    try {
      packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
      ""
    }

  companion object {
    fun start(context: Context) {
      val intent = Intent(context, ApiServerService::class.java).apply { action = ACTION_START_API_SERVER }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, ApiServerService::class.java).apply { action = ACTION_STOP_API_SERVER }
      context.startService(intent)
    }
  }
}
