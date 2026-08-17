/*
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

package com.google.ai.edge.gallery.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.api.OpenAiApiServer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Keep-alive foreground service.
 *
 * While the local OpenAI-compatible API server is running, this service runs in the foreground so
 * the system does not kill the app: it shows a persistent notification and is combined with the
 * "ignore battery optimizations" + vendor power-management whitelist guidance shown in the API
 * service screen to keep the server reachable even when the screen is off or the app is in the
 * background.
 *
 * The persistent notification is shown ONLY while [OpenAiApiServer] is actually running. If the
 * system restarts this service without the server being alive (e.g. a stale START_STICKY
 * restart), the service shuts itself down silently instead of showing a misleading notification.
 */
class ApiServerService : Service() {

  private val apiServer: OpenAiApiServer by lazy {
    EntryPointAccessors
      .fromApplication(applicationContext, ApiServerServiceEntryPoint::class.java)
      .openAiApiServer()
  }

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Only keep the persistent notification while the API server is actually running. When the
    // system restarts this service without the server alive, stop immediately instead of showing
    // a notification for a server that is not reachable.
    if (!apiServer.isRunning) {
      Log.i(TAG, "API server is not running; stopping keep-alive service")
      stopSelf()
      return START_NOT_STICKY
    }
    startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_MODEL_NAME)))
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    val nm = getSystemService(NotificationManager::class.java)
    nm.cancel(NOTIFICATION_ID)
  }

  private fun ensureNotificationChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.api_server_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.api_server_notification_channel_desc)
        setShowBadge(false)
      }
    nm.createNotificationChannel(channel)
  }

  private fun buildNotification(modelName: String?): Notification {
    // Tapping the notification brings the API service screen of the served model to the front.
    val contentIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
          .apply {
            // Deep link to the model page so the user lands on the API service screen.
            if (!modelName.isNullOrEmpty()) {
              data = android.net.Uri.parse("com.google.ai.edge.gallery://model/llm_chat/$modelName")
            }
          },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val contentText =
      if (modelName.isNullOrEmpty()) getString(R.string.api_server_notification_content)
      else getString(R.string.api_server_notification_content_with_model, modelName)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.gemma_logo)
      .setContentTitle(getString(R.string.api_server_notification_title))
      .setContentText(contentText)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(contentIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  companion object {
    private const val TAG = "AGApiServerService"
    private const val CHANNEL_ID = "api_server_channel"
    private const val NOTIFICATION_ID = 1001
    private const val EXTRA_MODEL_NAME = "extra_model_name"

    /** Starts the keep-alive foreground service (call while the app is in the foreground). */
    fun start(context: Context, modelName: String?) {
      try {
        val intent =
          Intent(context, ApiServerService::class.java).apply {
            putExtra(EXTRA_MODEL_NAME, modelName)
          }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to start ApiServerService", e)
      }
    }

    /** Stops the keep-alive foreground service. */
    fun stop(context: Context) {
      try {
        context.stopService(Intent(context, ApiServerService::class.java))
      } catch (e: Exception) {
        Log.w(TAG, "Failed to stop ApiServerService", e)
      }
    }
  }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApiServerServiceEntryPoint {
  fun openAiApiServer(): OpenAiApiServer
}