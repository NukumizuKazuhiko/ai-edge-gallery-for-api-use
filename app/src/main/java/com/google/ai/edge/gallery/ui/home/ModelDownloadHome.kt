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

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.modelmanager.ImportModelDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportingDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How long to keep the "upload .litertlm" hint visible before opening the SAF picker.
private const val SNACKBAR_HINT_DELAY_MS = 1000L

/**
 * The model download home page.
 *
 * This is the app's landing screen: it lists every LLM model the app can run and lets the user
 * download / delete them. Tapping the run button of a downloaded model opens the API service
 * startup page ([com.google.ai.edge.gallery.ui.apiserver.ApiServerScreen]). Models imported from
 * local files appear under the "Imported models" section.
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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

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

  // Imported models are not part of the allowlist; they live in the task model list.
  val importedModels =
    remember(uiState.modelImportingUpdateTrigger) {
      modelManagerViewModel
        .getAllModels()
        .filter { it.imported && it.isLlm && it.parentModelName.isNullOrEmpty() }
    }

  // Models usable right now, regardless of source: allowlist models that have finished
  // downloading plus all imported models. These are grouped together in a dedicated section at
  // the top of the home page so the user can reach runnable models without scrolling.
  val availableModels =
    remember(uiState.modelDownloadStatus, uiState.modelImportingUpdateTrigger) {
      val downloadedNames =
        uiState.modelDownloadStatus
          .filterValues { it.status == ModelDownloadStatusType.SUCCEEDED }
          .keys
      models.filter { downloadedNames.contains(it.name) } + importedModels
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

  // Import flow state.
  var showImportErrorDialog by remember { mutableStateOf(false) }
  var importErrorMessage by remember { mutableStateOf("") }
  var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
  var showImportConfigDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  var importInfo by remember { mutableStateOf<ImportedModel?>(null) }
  // Prevents the picker from being launched multiple times on rapid repeated taps.
  var isImportLauncherActive by remember { mutableStateOf(false) }

  val filePickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        val fileName = getFileName(context.contentResolver, uri)
        // Only `.litertlm` files are importable. `.task` and web-only (`-web`) models are rejected
        // to keep the on-device runtime guarantee (all LiteRT-LM models use the `.litertlm` format).
        val hasValidExtension = fileName != null && fileName.endsWith(".litertlm")
        if (!hasValidExtension) {
          importErrorMessage = context.getString(R.string.unsupported_file_type_error)
          showImportErrorDialog = true
        } else {
          pickedFileUri = uri
          showImportConfigDialog = true
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
              stringResource(R.string.home_subtitle),
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
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          // Guard against rapid repeated taps: only the first tap opens the picker.
          if (isImportLauncherActive) return@FloatingActionButton
          isImportLauncherActive = true
          // Show a hint that only `.litertlm` files are accepted. `showSnackbar` suspends until the
          // snackbar is dismissed, so it must run in its own coroutine and NOT gate the picker.
          scope.launch {
            snackbarHostState.showSnackbar(
              context.getString(R.string.import_model_hint_message)
            )
          }
          // Open the SAF picker (filtered to octet-stream, which is how the system classifies
          // .litertlm files) after a short delay. The picker callback still validates the
          // file-name extension as the final gate.
          scope.launch {
            delay(SNACKBAR_HINT_DELAY_MS)
            isImportLauncherActive = false
            filePickerLauncher.launch(arrayOf("application/octet-stream"))
          }
        },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      ) {
        Icon(
          Icons.AutoMirrored.Outlined.NoteAdd,
          contentDescription = stringResource(R.string.cd_import_model_button),
        )
      }
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(horizontal = 16.dp)
          .padding(top = innerPadding.calculateTopPadding()),
    ) {
      val isEmpty = models.isEmpty() && importedModels.isEmpty()
      if (isEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            if (uiState.loadingModelAllowlist) {
              stringResource(R.string.loading_model_list)
            } else {
              stringResource(R.string.home_empty_model_list)
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
            PaddingValues(top = 16.dp, bottom = innerPadding.calculateBottomPadding() + 96.dp),
        ) {
          if (availableModels.isNotEmpty()) {
            item(key = "available_models_label") {
              Text(
                stringResource(R.string.model_list_available_models_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp),
              )
            }
          }
          items(availableModels, key = { "available_${it.name}" }) { model ->
            ModelItem(
              model = model,
              task = null,
              modelManagerViewModel = modelManagerViewModel,
              onModelClicked = { onModelSelected(model) },
              onBenchmarkClicked = {},
              expanded = true,
              showBenchmarkButton = false,
              showDeleteButton = true,
              modelVariants = modelVariants.getOrDefault(model.name, listOf()),
            )
          }

          if (models.isNotEmpty()) {
            item(key = "models_label") {
              Text(
                stringResource(R.string.model_list_recommended_models_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp),
              )
            }
          }
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

  // Import config dialog (edit name / accelerators / sampling params).
  if (showImportConfigDialog && pickedFileUri != null) {
    ImportModelDialog(
      uri = pickedFileUri!!,
      onDismiss = { showImportConfigDialog = false },
      onDone = { info ->
        importInfo = info
        showImportConfigDialog = false
        showImportingDialog = true
      },
    )
  }

  // Importing (copying the file into the app dir) dialog.
  if (showImportingDialog && pickedFileUri != null && importInfo != null) {
    ModelImportingDialog(
      uri = pickedFileUri!!,
      info = importInfo!!,
      onDismiss = { showImportingDialog = false },
      onDone = { info ->
        val added = modelManagerViewModel.addImportedLlmModel(info = info)
        showImportingDialog = false
        scope.launch {
          snackbarHostState.showSnackbar(
            context.getString(
              if (added) R.string.model_imported_success
              else R.string.model_import_duplicate_message
            )
          )
        }
      },
    )
  }

  // Error dialog for unsupported files.
  if (showImportErrorDialog) {
    AlertDialog(
      onDismissRequest = { showImportErrorDialog = false },
      title = { Text(stringResource(R.string.unsupported_model_title)) },
      text = { Text(importErrorMessage) },
      confirmButton = {
        TextButton(onClick = { showImportErrorDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

private fun getFileName(resolver: android.content.ContentResolver, uri: Uri): String? {
  return try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
      } else {
        null
      }
    }
  } catch (e: Exception) {
    uri.lastPathSegment
  }
}