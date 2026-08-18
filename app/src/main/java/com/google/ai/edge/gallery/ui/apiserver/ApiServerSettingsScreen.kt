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

package com.google.ai.edge.gallery.ui.apiserver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.data.AppLocale
import com.google.ai.edge.gallery.data.AppLocaleStore
import com.google.ai.edge.gallery.data.ModelDownloadSource
import com.google.ai.edge.gallery.data.ModelDownloadSourceStore

/**
 * The keep-alive settings page.
 *
 * Opens from the settings button in the top-left of the home page. It lets the user grant the
 * permissions that keep the local API server alive in the background (notification permission,
 * battery-optimization whitelist, vendor power-management / background whitelist).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerSettingsScreen(onBackClicked: () -> Unit) {
  val context = LocalContext.current

  // --- Keep-alive state ---
  var isIgnoringBatteryOptimizations by remember { mutableStateOf(false) }
  var hasNotificationPermission by remember { mutableStateOf(false) }

  fun refreshKeepAliveState() {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
    hasNotificationPermission =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      refreshKeepAliveState()
    }

  // --- Model download source ---
  var downloadSource by remember { mutableStateOf(ModelDownloadSourceStore.get(context)) }

  // --- App language ---
  var appLocale by remember { mutableStateOf(AppLocaleStore.get(context)) }

  LaunchedEffect(Unit) {
    refreshKeepAliveState()
  }

  // Re-read battery-optimization / notification state whenever the screen resumes, because the
  // user changes it on an external system settings page (battery-optimization request or the
  // vendor power-management page). Without this, the button would keep showing the stale state
  // until the user leaves and re-enters the settings screen.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshKeepAliveState()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Text(stringResource(R.string.settings_title))
          }
        },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back))
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // --- App language ---
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(R.string.settings_language_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          val localeOptions =
            listOf(
              AppLocale.SYSTEM to stringResource(R.string.settings_language_system),
              AppLocale.ZH_RCN to stringResource(R.string.settings_language_zh_cn),
              AppLocale.EN to stringResource(R.string.settings_language_en),
            )
          localeOptions.forEach { (locale, label) ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier =
                Modifier
                  .fillMaxWidth()
                  .selectable(
                    selected = appLocale == locale,
                    onClick = {
                      appLocale = locale
                      AppLocaleStore.set(context, locale)
                      // Recreate the activity so every screen re-inflates its resources with the
                      // new locale. Skip the startup splash to avoid a blank flash during the
                      // recreate. attachBaseContext applies the stored locale on the next launch.
                      MainActivity.skipSplashOnNextCreate = true
                      (context as? ComponentActivity)?.recreate()
                    },
                  )
                  .padding(vertical = 4.dp),
            ) {
              RadioButton(
                selected = appLocale == locale,
                onClick = {
                  appLocale = locale
                  AppLocaleStore.set(context, locale)
                  MainActivity.skipSplashOnNextCreate = true
                  (context as? ComponentActivity)?.recreate()
                },
              )
              Text(label, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }

      // --- Model download source ---
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.download_source_title), style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(R.string.download_source_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
              Modifier
                .fillMaxWidth()
                .selectable(
                  selected = downloadSource == ModelDownloadSource.HF,
                  onClick = {
                    downloadSource = ModelDownloadSource.HF
                    ModelDownloadSourceStore.set(context, ModelDownloadSource.HF)
                  },
                )
                .padding(vertical = 4.dp),
          ) {
            RadioButton(
              selected = downloadSource == ModelDownloadSource.HF,
              onClick = {
                downloadSource = ModelDownloadSource.HF
                ModelDownloadSourceStore.set(context, ModelDownloadSource.HF)
              },
            )
            Text(stringResource(R.string.download_source_hf), style = MaterialTheme.typography.bodyMedium)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
              Modifier
                .fillMaxWidth()
                .selectable(
                  selected = downloadSource == ModelDownloadSource.MODELSCOPE,
                  onClick = {
                    downloadSource = ModelDownloadSource.MODELSCOPE
                    ModelDownloadSourceStore.set(context, ModelDownloadSource.MODELSCOPE)
                  },
                )
                .padding(vertical = 4.dp),
          ) {
            RadioButton(
              selected = downloadSource == ModelDownloadSource.MODELSCOPE,
              onClick = {
                downloadSource = ModelDownloadSource.MODELSCOPE
                ModelDownloadSourceStore.set(context, ModelDownloadSource.MODELSCOPE)
              },
            )
            Text(stringResource(R.string.download_source_modelscope), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      // --- Keep-alive ---
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.keepalive_title), style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(R.string.keepalive_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Notification permission.
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (hasNotificationPermission) stringResource(R.string.keepalive_notification_granted)
              else stringResource(R.string.keepalive_notification_missing),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            Button(
              onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
              },
              enabled = !hasNotificationPermission,
            ) {
              Text(stringResource(R.string.keepalive_notification_action))
            }
          }

          // Battery optimization whitelist.
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (isIgnoringBatteryOptimizations) stringResource(R.string.keepalive_battery_opt_ok)
              else stringResource(R.string.keepalive_battery_opt_missing),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            Button(
              onClick = {
                val intent =
                  Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                  )
                try {
                  context.startActivity(intent)
                } catch (e: Exception) {
                  Toast.makeText(context, context.getString(R.string.toast_open_battery_settings_failed), Toast.LENGTH_SHORT).show()
                }
              },
              enabled = !isIgnoringBatteryOptimizations,
            ) {
              Text(stringResource(R.string.keepalive_battery_opt_action))
            }
          }

          // Vendor power-management whitelist.
          Button(
            onClick = { openVendorPowerManagementSettings(context) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.keepalive_vendor_action))
          }

          // Vendor battery-behavior whitelist (OPPO / OnePlus "battery behavior").
          HorizontalDivider()
          Text(
            stringResource(R.string.keepalive_battery_behavior_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = { openVendorBatteryBehaviorSettings(context) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.keepalive_battery_behavior_action))
          }
        }
      }
    }
  }
}

/**
 * Opens the vendor-specific power-management / background-app whitelist settings so the user can
 * allow the app to keep running in the background. Falls back to the generic app-info settings.
 */
private fun openVendorPowerManagementSettings(context: Context) {
  val pkg = context.packageName
  val candidates =
    listOf(
      // MIUI / HyperOS
      Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName(
          "com.miui.securitycenter",
          "com.miui.permcenter.permissions.PermissionsEditorActivity",
        )
        .setData(Uri.parse("package:$pkg")),
      // Huawei / Honor
      Intent()
        .setClassName(
          "com.huawei.systemmanager",
          "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
      // OPPO / OnePlus / ColorOS
      Intent()
        .setClassName(
          "com.coloros.safecenter",
          "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
        ),
      Intent("com.oplus.safecenter.permission.startupapp")
        .setClassName(
          "com.oplus.safecenter",
          "com.oplus.safecenter.permission.startupapp.StartupAppListActivity",
        ),
      // vivo
      Intent()
        .setClassName(
          "com.vivo.permissionmanager",
          "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
      // Meizu
      Intent()
        .setClassName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
      // Samsung
      Intent()
        .setClassName(
          "com.samsung.android.lool",
          "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
      // Generic fallback: app details page (battery section reachable manually).
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

  for (intent in candidates) {
    if (intent.resolveActivity(context.packageManager) != null) {
      try {
        context.startActivity(intent)
        return
      } catch (e: Exception) {
        // Try the next candidate.
      }
    }
  }
  Toast.makeText(context, context.getString(R.string.toast_power_mgmt_not_found), Toast.LENGTH_SHORT).show()
}

/**
 * Opens the vendor "battery behavior" / background-activity whitelist settings so the user can
 * choose "allow all background behavior" (OPPO / OnePlus / ColorOS). Falls back to the generic
 * app-info settings page where the battery-behavior entry lives.
 */
private fun openVendorBatteryBehaviorSettings(context: Context) {
  val pkg = context.packageName
  val candidates =
    listOf(
      // OPPO / OnePlus / ColorOS battery behavior entries.
      Intent("com.coloros.safecenter.permission.startupapp")
        .setClassName(
          "com.coloros.safecenter",
          "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
        ),
      Intent("com.oplus.safecenter.permission.startupapp")
        .setClassName(
          "com.oplus.safecenter",
          "com.oplus.safecenter.permission.startupapp.StartupAppListActivity",
        ),
      // Generic fallback: app details page (battery section reachable manually).
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

  for (intent in candidates) {
    if (intent.resolveActivity(context.packageManager) != null) {
      try {
        context.startActivity(intent)
        return
      } catch (e: Exception) {
        // Try the next candidate.
      }
    }
  }
  Toast.makeText(context, context.getString(R.string.toast_battery_behavior_not_found), Toast.LENGTH_SHORT).show()
}
