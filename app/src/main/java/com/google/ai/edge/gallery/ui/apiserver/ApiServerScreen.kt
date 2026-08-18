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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.api.ApiServerViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType

/**
 * The API service startup page.
 *
 * Replaces the chat interface: instead of chatting in the app, the user starts a local
 * OpenAI-compatible HTTP server (backed by [com.google.ai.edge.gallery.api.OpenAiApiServer]) that
 * serves the currently selected on-device model to any OpenAI-compatible client on the LAN.
 *
 * This composable is used as the [com.google.ai.edge.gallery.customtasks.common.CustomTask.MainScreen]
 * of the LLM chat task, so it is rendered inside the task scaffold which already handles model
 * download, initialization and the top app bar (model selector / back button).
 */
@Composable
fun ApiServerScreen(
  data: CustomTaskData,
  apiServerViewModel: ApiServerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val uiState by data.modelManagerViewModel.uiState.collectAsState()
  val model = uiState.selectedModel

  val isRunning by apiServerViewModel.isRunning.collectAsState()
  val baseUrl by apiServerViewModel.baseUrl.collectAsState()
  val authToken by apiServerViewModel.authToken.collectAsState()
  val activeModelName by apiServerViewModel.activeModelName.collectAsState()
  var tokenInput by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    apiServerViewModel.loadAuthToken()
    tokenInput = authToken.orEmpty()
  }

  // If the user opens a different model while the server is running, stop the server so the
  // exposed model matches the newly opened one. When the user opens the same model that is
  // already being served (e.g. navigating back to the API page), the model is still online, so
  // leave the server running and jump straight into the API page without re-initializing.
  LaunchedEffect(model.name) {
    val targetName = model.displayName.ifEmpty { model.name }
    if (isRunning && activeModelName != null && activeModelName != targetName) {
      apiServerViewModel.stop()
    }
  }

  val downloadStatus = uiState.modelDownloadStatus[model.name]
  val isDownloaded = downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  val initStatus = uiState.modelInitializationStatus[model.name]
  val isInitialized = initStatus?.status == ModelInitializationStatusType.INITIALIZED
  val isInitializing = initStatus?.status == ModelInitializationStatusType.INITIALIZING
  val initError = initStatus?.error

  // Make sure the model is initialized so the server can actually serve it.
  LaunchedEffect(model.name, isDownloaded) {
    val status = initStatus?.status
    if (isDownloaded && status != ModelInitializationStatusType.INITIALIZED && status != ModelInitializationStatusType.INITIALIZING) {
      val task = data.modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.LLM_CHAT)?.task
      if (task != null) {
        data.modelManagerViewModel.initializeModel(
          context = context,
          task = task,
          model = model,
          onDone = {},
          onError = {},
        )
      }
    }
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // --- Model status ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.api_server_current_model), style = MaterialTheme.typography.labelLarge)
        Text(model.name, style = MaterialTheme.typography.titleLarge)
        Text(
          stringResource(R.string.api_server_version_runtime, model.version.ifEmpty { "unknown" }, model.runtimeType),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        when {
          isInitialized -> {
            Text(
              stringResource(R.string.api_server_model_ready),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          isInitializing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.api_server_model_initializing), style = MaterialTheme.typography.bodyMedium)
            }
          }
          initError != null -> {
            Text(
              stringResource(R.string.api_server_model_init_failed, initError),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
          else -> {
            Text(
              stringResource(R.string.api_server_model_not_initialized),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    // --- Server control ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Outlined.Dns, contentDescription = null)
          Text(stringResource(R.string.api_server_title), style = MaterialTheme.typography.titleMedium)
        }

        if (isRunning && baseUrl != null) {
          Text(
            stringResource(R.string.api_server_running),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = baseUrl!!,
              style = MaterialTheme.typography.bodyLarge,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f),
            )
            Icon(
              imageVector = Icons.Outlined.ContentCopy,
              contentDescription = stringResource(R.string.api_server_copy_address),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable { copyToClipboard(context, baseUrl!!) },
            )
          }
          Text(
            stringResource(R.string.api_server_lan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            stringResource(R.string.api_server_not_running),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Button(
          onClick = {
            if (isRunning) {
              apiServerViewModel.stop()
            } else {
              apiServerViewModel.start(model = model, taskId = BuiltInTaskId.LLM_CHAT)
            }
          },
          enabled = isRunning || (isDownloaded && isInitialized),
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (isRunning) {
            Icon(Icons.Outlined.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.api_server_stop))
          } else {
            Text(
              if (isInitialized) stringResource(R.string.api_server_start)
              else stringResource(R.string.api_server_wait_ready)
            )
          }
        }

        if (!isRunning && !isInitialized && isDownloaded) {
          Text(
            stringResource(R.string.api_server_initializing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // --- Auth token ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.api_server_auth_token_title), style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(R.string.api_server_auth_token_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = tokenInput,
          onValueChange = { tokenInput = it },
          label = { Text(stringResource(R.string.api_server_token_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(
          onClick = { apiServerViewModel.setAuthToken(tokenInput) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.api_server_save_token))
        }
      }
    }

    // --- Usage ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.api_server_usage_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.api_server_list_models), style = MaterialTheme.typography.bodySmall)
        Text(
          "GET {base}/v1/models",
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
        )
        Text(stringResource(R.string.api_server_chat_streaming), style = MaterialTheme.typography.bodySmall)
        Text(
          "POST {base}/v1/chat/completions",
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
        )
        HorizontalDivider()
        Text(stringResource(R.string.api_server_curl_example), style = MaterialTheme.typography.bodySmall)
        Text(
          stringResource(R.string.api_server_curl_body, model.name),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          stringResource(R.string.api_server_health_debug),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun copyToClipboard(context: Context, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("api_url", text))
  Toast.makeText(context, context.getString(R.string.api_server_copied), Toast.LENGTH_SHORT).show()
}
