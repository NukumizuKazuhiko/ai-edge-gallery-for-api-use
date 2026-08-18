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

package com.google.ai.edge.gallery.data

import android.content.Context

/**
 * The source from which model files are downloaded.
 *
 * - [HF] downloads from Hugging Face (the default, "native direct" source).
 * - [MODELSCOPE] downloads from ModelScope (魔搭社区). Currently only the Gemma 4
 *   E2B / E4B LiteRT-LM models are mirrored there, so downloading any other model falls back to
 *   Hugging Face automatically.
 */
enum class ModelDownloadSource {
  HF,
  MODELSCOPE,
}

/** Persists the user's chosen model download source in SharedPreferences. */
object ModelDownloadSourceStore {
  private const val PREFS_NAME = "model_download_source_prefs"
  private const val KEY_DOWNLOAD_SOURCE = "download_source"
  private const val VALUE_HF = "hf"
  private const val VALUE_MODELSCOPE = "modelscope"

  fun get(context: Context): ModelDownloadSource {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return when (prefs.getString(KEY_DOWNLOAD_SOURCE, VALUE_HF)) {
      VALUE_MODELSCOPE -> ModelDownloadSource.MODELSCOPE
      else -> ModelDownloadSource.HF
    }
  }

  fun set(context: Context, source: ModelDownloadSource) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_DOWNLOAD_SOURCE, source.toPrefValue()).apply()
  }

  private fun ModelDownloadSource.toPrefValue(): String =
    when (this) {
      ModelDownloadSource.HF -> VALUE_HF
      ModelDownloadSource.MODELSCOPE -> VALUE_MODELSCOPE
    }
}

/**
 * Maps Hugging Face model download URLs to their ModelScope (魔搭社区) mirrors when the ModelScope
 * download source is selected.
 *
 * ModelScope currently mirrors only the Gemma 4 E2B / E4B LiteRT-LM models in
 * `venshell/gemma-4-it-litert-lm`. Models that are not mirrored there keep their original Hugging
 * Face URL so downloads still work.
 */
object ModelScopeUrlMapper {
  private const val MODELSCOPE_MODEL = "venshell/gemma-4-it-litert-lm"
  private const val MODELSCOPE_REVISION = "master"
  private const val MODELSCOPE_REFERER = "https://www.modelscope.cn/"

  // Model name -> the mirror's file name (same as on Hugging Face).
  private val SUPPORTED_MODEL_NAMES =
    mapOf(
      "Gemma-4-E2B-it" to "gemma-4-E2B-it.litertlm",
      "Gemma-4-E4B-it" to "gemma-4-E4B-it.litertlm",
    )

  /** Returns the ModelScope download URL for [modelName], or `null` if not mirrored. */
  fun resolveModelScopeUrl(modelName: String): String? {
    val fileName = SUPPORTED_MODEL_NAMES[modelName] ?: return null
    return "https://modelscope.cn/models/$MODELSCOPE_MODEL/resolve/$MODELSCOPE_REVISION/$fileName"
  }

  /** Whether [url] points at a ModelScope download (and thus needs the ModelScope referer). */
  fun isModelScopeUrl(url: String): Boolean {
    return url.startsWith("https://modelscope.cn/models/")
  }

  /** The Referer header required by ModelScope for range/direct downloads. */
  fun referer(): String = MODELSCOPE_REFERER
}
