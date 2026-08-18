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

package com.google.ai.edge.gallery.ui.modelmanager

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ensureValidFileName
import com.google.ai.edge.gallery.common.humanReadableSize
import com.google.ai.edge.gallery.common.isPixel10
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.importedModel
import com.google.ai.edge.gallery.proto.llmConfig
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import kotlinx.coroutines.CancellationException

private const val TAG = "AGImportModelDialog"

/** Accelerators offered in the import dialog; Pixel 10 devices cannot use GPU for LiteRT models. */
private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU, Accelerator.NPU)
  } else {
    // GPU first so the default (first option) matches the app-wide default accelerator for
    // allowlist models (`DEFAULT_ACCELERATORS = [GPU]`).
    listOf(Accelerator.GPU, Accelerator.CPU, Accelerator.NPU)
  }

/** Default config editors shown for an imported LLM model. */
private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 1f,
      sliderMax = 100f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_IMAGE, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_AUDIO, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_TINY_GARDEN, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_MOBILE_ACTIONS, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_SPECULATIVE_DECODING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )

/**
 * Dialog that lets the user review and edit the metadata of a picked `.litertlm` model file before
 * importing it. Shows a config editor panel (name, sampling params, accelerators) and confirms the
 * import.
 */
@Composable
fun ImportModelDialog(
  uri: Uri,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
  initialFileName: String? = null,
) {
  val context = LocalContext.current
  val info = remember { getFileInfoFromUri(context = context, uri = uri) }
  val fileName by remember {
    mutableStateOf(ensureValidFileName(initialFileName ?: info.displayName.ifEmpty { "model.litertlm" }))
  }
  val fileSize by remember { mutableStateOf(info.fileSize) }

  val initialValues: Map<String, Any> = remember {
    mutableMapOf<String, Any>().apply {
      for (config in IMPORT_CONFIGS_LLM) {
        put(config.key.label, config.defaultValue)
      }
      put(ConfigKeys.NAME.label, fileName)
      put(ConfigKeys.MODEL_TYPE.label, "LLM")
    }
  }
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        // File summary.
        Text(
          "${fileName}（${fileSize.humanReadableSize()}）",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Default configs for users to set.
        ConfigEditorsPanel(configs = IMPORT_CONFIGS_LLM, values = values)

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) }
          Button(
            onClick = {
              val supportedAccelerators =
                values.stringValue(ConfigKeys.COMPATIBLE_ACCELERATORS)
                  .split(",")
                  .map { it.trim() }
                  .filter { it.isNotEmpty() }
              val importedModel =
                importedModel {
                  this.fileName = fileName
                  this.fileSize = fileSize
                  this.llmConfig = llmConfig {
                    compatibleAccelerators += supportedAccelerators
                    this.defaultMaxTokens = values.intValue(ConfigKeys.DEFAULT_MAX_TOKENS)
                    this.defaultTopk = values.intValue(ConfigKeys.DEFAULT_TOPK)
                    this.defaultTopp = values.floatValue(ConfigKeys.DEFAULT_TOPP)
                    this.defaultTemperature =
                      values.floatValue(ConfigKeys.DEFAULT_TEMPERATURE)
                    this.supportImage = values.boolValue(ConfigKeys.SUPPORT_IMAGE)
                    this.supportAudio = values.boolValue(ConfigKeys.SUPPORT_AUDIO)
                    this.supportTinyGarden = values.boolValue(ConfigKeys.SUPPORT_TINY_GARDEN)
                    this.supportMobileActions = values.boolValue(ConfigKeys.SUPPORT_MOBILE_ACTIONS)
                    this.supportThinking = values.boolValue(ConfigKeys.SUPPORT_THINKING)
                    this.supportSpeculativeDecoding =
                      values.boolValue(ConfigKeys.SUPPORT_SPECULATIVE_DECODING)
                  }
                }
              onDone(importedModel)
            },
          ) {
            Text(stringResource(R.string.import_action))
          }
        }
      }
    }
  }
}

/** Dialog shown while the picked file is being copied into the app's imports directory. */
@Composable
fun ModelImportingDialog(
  uri: Uri,
  info: ImportedModel,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
) {
  var error by remember { mutableStateOf("") }
  val context = LocalContext.current
  var progress by remember { mutableFloatStateOf(0f) }
  var finished by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    val copied =
      copyFileToImports(
        context = context,
        fileName = info.fileName,
        fileSize = info.fileSize,
        uri = uri,
        onProgress = { progress = it },
        onError = { error = it },
      )
    if (copied) {
      finished = true
      onDone(info)
    }
  }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = onDismiss,
  ) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        if (error.isEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${info.fileName}（${info.fileSize.humanReadableSize()}）",
              style = MaterialTheme.typography.labelSmall,
            )
            LinearProgressIndicator(
              progress = { progress },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
          }
        } else {
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Error,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              error,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDismiss() }) { Text(stringResource(R.string.close)) }
          }
        }

        // Spinner while copying but before onDone has been invoked (to avoid a flicker).
        if (!finished && error.isEmpty()) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
          )
        }
      }
    }
  }
}

private data class FileInfo(val fileSize: Long, val displayName: String)

private fun getFileInfoFromUri(context: android.content.Context, uri: Uri): FileInfo {
  val contentResolver = context.contentResolver
  var fileSize = 0L
  var displayName = ""
  try {
    contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
          fileSize = cursor.getLong(sizeIndex)
          val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
          displayName = cursor.getString(nameIndex)
        }
      }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to read file info from $uri", e)
  }
  return FileInfo(fileSize = fileSize, displayName = displayName)
}

private suspend fun copyFileToImports(
  context: android.content.Context,
  fileName: String,
  fileSize: Long,
  uri: Uri,
  onProgress: (Float) -> Unit,
  onError: (String) -> Unit,
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
  try {
    val importsDir = java.io.File(context.getExternalFilesDir(null), com.google.ai.edge.gallery.data.IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }
    val outputFile = java.io.File(importsDir, fileName)
    val openedStream = context.contentResolver.openInputStream(uri)
    val inputStream = openedStream ?: run {
      onError(context.getString(R.string.failed_to_import))
      return@withContext false
    }
    try {
      val outputStream = java.io.FileOutputStream(outputFile)
      try {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead: Int
        var importedBytes = 0L
        var lastSetProgressTs = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          importedBytes += bytesRead
          val curTs = System.currentTimeMillis()
          if (curTs - lastSetProgressTs > 200) {
            lastSetProgressTs = curTs
            if (fileSize > 0L) {
              onProgress(importedBytes.toFloat() / fileSize.toFloat())
            }
          }
        }
      } finally {
        outputStream.close()
      }
    } finally {
      inputStream.close()
    }
    onProgress(1f)
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.e(TAG, "Failed to import model $fileName", e)
    onError(context.getString(R.string.failed_to_import))
    false
  }
}

/** Reads a value from the editor map and converts it to the requested type. */
@Suppress("UNCHECKED_CAST")
private fun <T> SnapshotStateMap<String, Any>.typedValue(
  configKey: ConfigKey,
  valueType: ValueType,
): T {
  return convertValueToTargetType(
      value = this.getValue(configKey.label),
      valueType = valueType,
    )
    as T
}

private fun SnapshotStateMap<String, Any>.stringValue(configKey: ConfigKey): String =
  typedValue(configKey, ValueType.STRING)

private fun SnapshotStateMap<String, Any>.intValue(configKey: ConfigKey): Int =
  typedValue(configKey, ValueType.INT)

private fun SnapshotStateMap<String, Any>.floatValue(configKey: ConfigKey): Float =
  typedValue(configKey, ValueType.FLOAT)

private fun SnapshotStateMap<String, Any>.boolValue(configKey: ConfigKey): Boolean =
  typedValue(configKey, ValueType.BOOLEAN)