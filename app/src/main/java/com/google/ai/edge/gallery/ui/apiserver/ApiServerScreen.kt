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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
  var tokenInput by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    apiServerViewModel.loadAuthToken()
    tokenInput = authToken.orEmpty()
  }

  // If the user switches to another model while the server is running, stop the server so the
  // exposed model always matches the one the user sees.
  LaunchedEffect(model.name) {
    if (isRunning) {
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
        Text("当前模型", style = MaterialTheme.typography.labelLarge)
        Text(model.name, style = MaterialTheme.typography.titleLarge)
        Text(
          "版本: ${model.version.ifEmpty { "unknown" }} · 运行库: ${model.runtimeType}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        when {
          isInitialized -> {
            Text(
              "模型已就绪，可启动 API 服务。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          isInitializing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
              Spacer(modifier = Modifier.width(8.dp))
              Text("正在加载模型…", style = MaterialTheme.typography.bodyMedium)
            }
          }
          initError != null -> {
            Text(
              "模型初始化失败: $initError",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
          else -> {
            Text(
              "模型尚未初始化。下载完成后会自动加载，请稍候。",
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
          Text("OpenAI 兼容 API 服务", style = MaterialTheme.typography.titleMedium)
        }

        if (isRunning && baseUrl != null) {
          Text(
            "服务运行中",
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
              contentDescription = "复制地址",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable { copyToClipboard(context, baseUrl!!) },
            )
          }
          Text(
            "同一局域网内任意 OpenAI 兼容客户端（curl / Python openai 库等）都可访问。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            "服务未启动",
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
            Text("停止服务")
          } else {
            Text(if (isInitialized) "启动服务" else "等待模型就绪…")
          }
        }

        if (!isRunning && !isInitialized && isDownloaded) {
          Text(
            "模型正在初始化，初始化完成后即可启动服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // --- Auth token ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("访问令牌（可选）", style = MaterialTheme.typography.titleMedium)
        Text(
          "设置后，客户端请求需携带 Authorization: Bearer <token>。留空则不鉴权。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = tokenInput,
          onValueChange = { tokenInput = it },
          label = { Text("Token") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(
          onClick = { apiServerViewModel.setAuthToken(tokenInput) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("保存令牌")
        }
      }
    }

    // --- Usage ---
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("使用方法", style = MaterialTheme.typography.titleMedium)
        Text("列出模型：", style = MaterialTheme.typography.bodySmall)
        Text(
          "GET {base}/v1/models",
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
        )
        Text("对话（支持流式）：", style = MaterialTheme.typography.bodySmall)
        Text(
          "POST {base}/v1/chat/completions",
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
        )
        HorizontalDivider()
        Text("curl 示例：", style = MaterialTheme.typography.bodySmall)
        Text(
          "curl http://<本机IP>:8080/v1/chat/completions \\\n" +
            "  -H \"Content-Type: application/json\" \\\n" +
            "  -d '{\"model\": \"${model.name}\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}], \"stream\": true}'",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          "健康检查：GET {base}/health，调试信息：GET {base}/debug",
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
  Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
