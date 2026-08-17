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

package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * The model download home page.
 *
 * This is the app's landing screen: it lists every LLM model the app can run and lets the user
 * download / delete them. Tapping the run button of a downloaded model opens the API service
 * startup page ([com.google.ai.edge.gallery.ui.apiserver.ApiServerScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadHome(
  modelManagerViewModel: ModelManagerViewModel,
  onModelSelected: (Model) -> Unit,
  onApiServerClicked: () -> Unit,
  onSettingsClicked: () -> Unit,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()

  // Only show models that are usable by the active LLM chat task. Models that are registered only
  // for deleted task types (tiny garden, mobile actions, etc.) are not listed on the home page.
  val activeTaskModelNames =
    remember(uiState) {
      modelManagerViewModel
        .getCustomTaskByTaskId(BuiltInTaskId.LLM_CHAT)
        ?.task
        ?.models
        ?.map { it.name }
        ?.toSet()
        .orEmpty()
    }

  val models =
    remember(uiState.loadingModelAllowlist, uiState.modelImportingUpdateTrigger) {
      modelManagerViewModel.allowlistModels
        .filter { it.isLlm }
        .filter { it.parentModelName.isNullOrEmpty() }
        .filter { activeTaskModelNames.contains(it.name) }
    }

  // Group variants (models sharing a parentModelName) under their top-level model.
  val modelVariants by
    remember(uiState.modelImportingUpdateTrigger) {
      derivedStateOf {
        val all = modelManagerViewModel.allowlistModels
        all.filter { it.parentModelName != null }.groupBy { it.parentModelName!! }
      }
    }

  // Track expanded state per model; auto-expand freshly downloaded models so the run button shows.
  val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
  LaunchedEffect(uiState.modelDownloadStatus) {
    for ((name, status) in uiState.modelDownloadStatus) {
      if (status.status == ModelDownloadStatusType.SUCCEEDED && !expandedStates.containsKey(name)) {
        expandedStates[name] = true
      }
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    topBar = {
      CenterAlignedTopAppBar(
        navigationIcon = {
          IconButton(onClick = onSettingsClicked) {
            Icon(
              Icons.Outlined.Settings,
              contentDescription = stringResource(R.string.cd_app_settings),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AI Edge Gallery", style = MaterialTheme.typography.titleLarge)
            Text(
              "模型下载 · 本地 API 服务",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) {
            Icon(
              Icons.Outlined.Refresh,
              contentDescription = stringResource(R.string.cd_refresh),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          IconButton(onClick = onApiServerClicked) {
            Icon(
              Icons.Outlined.Dns,
              contentDescription = stringResource(R.string.cd_open_api_server),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(horizontal = 16.dp)
          .padding(top = innerPadding.calculateTopPadding()),
    ) {
      if (models.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            if (uiState.loadingModelAllowlist) {
              "正在加载模型列表…"
            } else {
              "暂无可用模型，请检查网络后点击右上角刷新。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
          )
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding =
            PaddingValues(top = 16.dp, bottom = innerPadding.calculateBottomPadding() + 24.dp),
        ) {
          items(models, key = { it.name }) { model ->
            val isDownloaded =
              uiState.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
            ModelItem(
              model = model,
              task = null,
              modelManagerViewModel = modelManagerViewModel,
              onModelClicked = { onModelSelected(model) },
              onBenchmarkClicked = {},
              expanded = expandedStates.getOrDefault(model.name, isDownloaded),
              showBenchmarkButton = false,
              showDeleteButton = true,
              onExpanded = { expandedStates[model.name] = it },
              modelVariants = modelVariants.getOrDefault(model.name, listOf()),
            )
          }
        }
      }
    }
  }
}