/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.api.ApiServerViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.apiserver.ApiServerSettingsScreen
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.home.ModelDownloadHome
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_HOMESCREEN,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    // Home: model download list.
    composable(route = ROUTE_HOMESCREEN) {
      // Observe the local API server so the home page can show a "back to API" affordance while a
      // model is being served, letting the user return to the API page of that model.
      val apiServerViewModel: ApiServerViewModel = hiltViewModel()
      val apiRunning by apiServerViewModel.isRunning.collectAsState()
      val apiModelName by apiServerViewModel.activeModelName.collectAsState()
      ModelDownloadHome(
        modelManagerViewModel = modelManagerViewModel,
        onModelSelected = { model ->
          navController.navigate("$ROUTE_MODEL/${BuiltInTaskId.LLM_CHAT}/${model.name}")
        },
        onSettingsClicked = { navController.navigate(ROUTE_SETTINGS) },
        onApiServerClicked = {
          // Open the API service page of the first downloaded LLM model.
          val model =
            modelManagerViewModel.allowlistModels.firstOrNull {
              modelManagerUiState.modelDownloadStatus[it.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            }
          if (model != null) {
            navController.navigate("$ROUTE_MODEL/${BuiltInTaskId.LLM_CHAT}/${model.name}")
          }
        },
        showReturnToApiServer = apiRunning,
        onReturnToApiServer = {
          // Navigate back to the API page of the model currently being served. Match by display
          // name (what the server advertises) or by the internal name.
          val servedModel =
            modelManagerViewModel.allowlistModels.firstOrNull {
              it.displayName == apiModelName || it.name == apiModelName
            } ?: modelManagerViewModel.getAllModels().firstOrNull {
              it.displayName == apiModelName || it.name == apiModelName
            }
          if (servedModel != null) {
            navController.navigate("$ROUTE_MODEL/${BuiltInTaskId.LLM_CHAT}/${servedModel.name}")
          }
        },
      )
    }

    // Model page: download panel while downloading, then the API service startup page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      val initialModel =
        modelManagerViewModel.allowlistModels.find { it.name == modelName }
          ?: modelManagerViewModel.getModelByName(name = modelName)
      if (initialModel != null) {
        LaunchedEffect(modelName) {
          modelManagerViewModel.selectModel(initialModel)
          // Register the model into the task so it is tracked by the task scaffold (cleanup,
          // model selector) even when the model belongs to another task type in the allowlist.
          val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
          if (customTask != null && customTask.task.models.none { it.name == initialModel.name }) {
            customTask.task.models.add(initialModel)
          }
        }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          var disableAppBarControls by remember { mutableStateOf(false) }
          var hideTopBar by remember { mutableStateOf(false) }
          var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
          CustomTaskScreen(
            task = customTask.task,
            modelManagerViewModel = modelManagerViewModel,
            onNavigateUp = {
              if (customNavigateUpCallback != null) {
                customNavigateUpCallback?.invoke()
              } else {
                navController.navigateUp()

                // Clean up models that are no longer needed. The model currently being served by
                // the local API server is kept online automatically by ModelManagerViewModel (it
                // skips cleanup for a model the server is actively serving), so we do not have to
                // special-case it here and the model stays available even after leaving the page.
                for (curModel in customTask.task.models) {
                  val instanceToCleanUp = curModel.instance
                  scope.launch(Dispatchers.Default) {
                    modelManagerViewModel.cleanupModel(
                      context = context,
                      task = customTask.task,
                      model = curModel,
                      instanceToCleanUp = instanceToCleanUp,
                    )
                  }
                }
              }
            },
            disableAppBarControls = disableAppBarControls,
            hideTopBar = hideTopBar,
            useThemeColor = customTask.task.useThemeColor,
          ) { bottomPadding ->
            customTask.MainScreen(
              data =
                CustomTaskData(
                  modelManagerViewModel = modelManagerViewModel,
                  bottomPadding = bottomPadding,
                  setAppBarControlsDisabled = { disableAppBarControls = it },
                  setTopBarVisible = { hideTopBar = !it },
                  setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                )
            )
          }
        }
      }
    }

    // Global settings page (opened from the settings button in the home page top-left).
    composable(route = ROUTE_SETTINGS) {
      ApiServerSettingsScreen(onBackClicked = { navController.navigateUp() })
    }
  }

  // Handle incoming intents for deep links (com.google.ai.edge.gallery://model/<taskId>/<modelName>)
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  if (data != null && modelManagerUiState.tasks.isNotEmpty()) {
    intent.data = null
    val uriStr = data.toString()
    Log.d(TAG, "navigation link clicked: $data")
    if (uriStr.startsWith("com.google.ai.edge.gallery://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        (modelManagerViewModel.allowlistModels.find { it.name == modelName }
          ?: modelManagerViewModel.getModelByName(name = modelName))?.let { model ->
          navController.navigate("$ROUTE_MODEL/${taskId}/${model.name}")
        }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else {
      Log.d(TAG, "Ignoring unsupported deep link: $uriStr")
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    topBar = {
      androidx.compose.animation.AnimatedVisibility(
        !hideTopBar,
        enter = androidx.compose.animation.slideInVertically { -it },
        exit = androidx.compose.animation.slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          shouldShowHistoryButton = false,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      androidx.compose.animation.core.animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED,
        transitionSpec = {
          (fadeIn(animationSpec = tween(300)) +
            slideIntoContainer(
              towards = AnimatedContentTransitionScope.SlideDirection.Up,
              animationSpec = tween(300),
            )) togetherWith
            (fadeOut(animationSpec = tween(300)) +
              slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Down,
                animationSpec = tween(300),
              ))
        },
        label = "DownloadToContent",
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}