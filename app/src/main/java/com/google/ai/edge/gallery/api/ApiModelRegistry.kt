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

package com.google.ai.edge.gallery.api

import com.google.ai.edge.gallery.data.Model
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A process-wide registry of models available to the local OpenAI-compatible API server.
 *
 * The app's model library (allowlist models + imported models + download status) is owned by the
 * [com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel]. That ViewModel pushes the
 * latest snapshot of downloadable LLM models here whenever the library changes (allowlist refresh,
 * download, import, delete).
 *
 * NOTE: the local API server no longer reads this registry for `/v1/models` — it advertises only
 * the single model currently being served. This registry currently has no consumer and is kept
 * only as the ModelManagerViewModel's model-library snapshot; revisit if it becomes dead code.
 */
@Singleton
class ApiModelRegistry
@Inject
constructor() {
  @Volatile private var models: List<Model> = emptyList()

  /** Replaces the registry content with the latest snapshot of available models. */
  fun updateModels(models: List<Model>) {
    this.models = models.distinctBy { it.name }
  }

  /** The models currently available to the API server, keyed by their unique [Model.name]. */
  fun getModels(): List<Model> = models
}