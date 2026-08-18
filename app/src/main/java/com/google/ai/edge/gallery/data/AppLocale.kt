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
import android.content.res.Configuration
import java.util.Locale

/**
 * The app display language.
 *
 * - [SYSTEM] follows the device system language (the default); unsupported languages fall back
 *   to English (`values/strings.xml`).
 * - [ZH_RCN] forces Simplified Chinese (`values-zh-rCN/strings.xml`).
 * - [EN] forces English (`values/strings.xml`).
 *
 * [tag] must be a well-formed BCP-47 language tag ([Locale.forLanguageTag] input), e.g. `zh-CN`.
 * Android resource qualifiers use the `zh-rCN` form, but the framework maps a `zh-CN` locale onto
 * the `values-zh-rCN` directory automatically.
 */
enum class AppLocale(val tag: String?) {
  SYSTEM(null),
  ZH_RCN("zh-CN"),
  EN("en"),
}

/** Persists the user's chosen app language in SharedPreferences. */
object AppLocaleStore {
  private const val PREFS_NAME = "app_locale_prefs"
  private const val KEY_LOCALE = "app_locale"
  private const val VALUE_SYSTEM = "system"
  private const val VALUE_ZH_RCN = "zh-CN"
  private const val VALUE_EN = "en"

  fun get(context: Context): AppLocale {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return when (prefs.getString(KEY_LOCALE, VALUE_SYSTEM)) {
      VALUE_ZH_RCN -> AppLocale.ZH_RCN
      VALUE_EN -> AppLocale.EN
      else -> AppLocale.SYSTEM
    }
  }

  fun set(context: Context, locale: AppLocale) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_LOCALE, locale.toPrefValue()).apply()
  }

  private fun AppLocale.toPrefValue(): String =
    when (this) {
      AppLocale.SYSTEM -> VALUE_SYSTEM
      AppLocale.ZH_RCN -> VALUE_ZH_RCN
      AppLocale.EN -> VALUE_EN
    }
}

/** Applies the user-selected app language on top of a base context. */
object AppLocaleHelper {
  /**
   * Returns a context whose resources use the user-selected locale. Falls back to [base] unchanged
   * when the app follows the system language.
   *
   * Must be called from `Application.attachBaseContext` so every activity context inherits the
   * locale before any resource is inflated.
   */
  fun applyLocale(base: Context): Context {
    val locale = AppLocaleStore.get(base)
    val tag = locale.tag ?: return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return base.createConfigurationContext(configuration)
  }
}
