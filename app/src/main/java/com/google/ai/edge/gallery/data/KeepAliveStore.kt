/*
 * Copyright 2026 NukumizuKazuhiko
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

package com.google.ai.edge.gallery.data

import android.content.Context

/**
 * Persists the independent "background keep-alive" switch.
 *
 * Keep-alive is driven entirely by the local API server lifecycle: starting the server enables it
 * ([setEnabled] with `true`) and launches the foreground [ApiServerService]; it stays on until the
 * process is killed (there is no UI toggle to turn it off). This helps the process survive
 * OnePlus / ColorOS background reaping and the screen being off, so the local API server becomes
 * reachable again without a cold start.
 */
object KeepAliveStore {
  private const val PREFS_NAME = "keep_alive_prefs"
  private const val KEY_ENABLED = "keep_alive_enabled"

  /** Returns true when the independent background keep-alive switch is on. */
  fun isEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

  /** Persists the keep-alive switch. */
  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }
}
