# 多语言（i18n）与文案管理规范

> 本文档定义 AI Edge Gallery for API Use 项目的文案管理与多语言支持规范：
> 资源如何组织、如何新增一种语言、哪些术语禁止翻译、以及代码中如何正确取用文案。
> 适用对象：所有新增或修改 UI 文案的提交。

## 1. 资源组织（单一真源）

所有用户可见文案必须放入 Android 字符串资源，禁止在 `@Composable`、`Toast`、
`contentDescription`、`AlertDialog` 中硬编码自然语言文本。

| 资源文件 | 语言 | 角色 |
| :--- | :--- | :--- |
| `app/src/main/res/values/strings.xml` | 英文 | **默认语言 / 回退语言**，必须存在全部 key |
| `app/src/main/res/values-zh-rCN/strings.xml` | 简体中文 | 仅覆盖已本地化的 key |

规则：

- **默认语言必须是英文。** 所有字符串先在 `values/strings.xml` 落地英文原文，
  再在 `values-zh-rCN/strings.xml` 提供中文翻译。
- **资源 key 是唯一标识，不得删除默认值**。若默认语言缺失某个 key，
  该语言（例如繁体中文、日文）设备上会直接崩溃。
- 不支持的语言（如 `-zh-rTW`、`-ja`）自动回退英文，无需额外文件。
- 新增语言时新建 `values-<locale>/strings.xml`，只覆盖已翻译条目，不拷贝全量。

## 1.1 语言选择（设置页入口）

设置页提供三档语言选择（`settings_language_*`）：

| 选项 | 含义 |
| :--- | :--- |
| 跟随系统 | 使用设备系统语言；不支持的 locale 回退英文 |
| 简体中文 | 强制 `values-zh-rCN` |
| English | 强制 `values` |

实现机制（`app/src/main/java/com/google/ai/edge/gallery/data/AppLocale.kt`）：

- `AppLocale` 枚举 + `AppLocaleStore`（SharedPreferences 持久化）+ `AppLocaleHelper.applyLocale()`。
- `GalleryApplication.attachBaseContext` 与 `MainActivity.attachBaseContext` 均调用
  `applyLocale()`：前者覆盖进程冷启动，后者覆盖语言切换后的 `recreate()`。
- 切换语言后 `MainActivity.recreate()` 重建 Activity 使 `stringResource` 立即生效；
  重建前设置 `MainActivity.skipSplashOnNextCreate`，跳过启动 splash 并应用
  `Theme.Gallery`（避免闪白屏与残留 ActionBar）。
- `MainActivity.skipSplashOnNextCreate` 是进程级静态标志，仅在语言切换的 `recreate()`
  前由设置页写入，并在 `MainActivity.onCreate` 开头读取后立即清零（一次性消费）。
  若 `recreate()` 未发生（如上下文转型失败），标志会在下次冷启动时被消费导致跳过
  splash——设置页用 `isSwitchingLocale` 状态保证同一语言仅触发一次 recreate。
- 选择「跟随系统」时 `applyLocale()` 原样返回 base context，完全跟随系统。

**新增一种语言时**：在 `values/strings.xml` 之后新建 `values-<locale>/strings.xml`
（覆盖已翻译条目），并在 `AppLocale` 枚举与设置页 `localeOptions` 中增加对应选项。

## 2. 专有名词不翻译清单

以下术语在中文翻译中**保留英文原文**，不得音译或意译：

| 类别 | 术语 |
| :--- | :--- |
| 加速器 | `GPU`、`CPU`、`NPU` |
| 采样参数 | `Token`、`TopK`、`TopP` |
| 服务 / 平台 | `OpenAI`、`Hugging Face`、`ModelScope`（魔搭）、`GitHub` |
| 命令行工具 | `curl` |
| 运行时 / 模型 | `LiteRT`、`Gemma`、`Tiny Garden`、`Mobile Actions`、`LLM`、`AICore` |
| 厂商 | `OPPO`、`OnePlus` |
| 协议字段 | `Authorization`、`Bearer`、`GET`、`POST`、`Content-Type` |

规则：

- 专有名词首次出现时，中文文案可补充括号说明（如「魔搭社区（ModelScope）」），
  但主词保留英文。
- `translatable="false"` 仅用于品牌名（如 `app_name`）与代码指令（如 URL），
  其余一律 `translatable="true"`。
- 配置项标签（`config_label_*`）默认英文且已翻译中文，新增配置项时必须同时补两个语言。

## 3. 命名约定

资源 key 采用 `snake_case`，按页面 / 功能前缀分组：

| 前缀 | 所属页面 / 功能 |
| :--- | :--- |
| `home_` | 模型下载首页 |
| `config_` / `config_label_` | 配置对话框与配置项 |
| `api_server_` | API 服务页与前台服务通知 |
| `keepalive_` | 设置页保活区块 |
| `download_source_` | 设置页下载源区块 |
| `toast_` | Toast 提示 |
| `import_model_` / `model_imported_` / `unsupported_` | 模型导入流程 |
| `settings_language_` | 设置页语言选择 |
| `cd_` | 无障碍 contentDescription |

规则：

- key 只描述文案含义（`home_empty_model_list`），不携带语言或页面编号。
- 每条资源必须写 `description`，说明用途与占位符含义（如 `%1$s`）。**默认语言
  `values/strings.xml` 必须写**；`values-zh-rCN/strings.xml` 等翻译文件是纯覆盖，
  允许省略 `description`（不重复默认语言的描述）。
- 占位符用 `%1$s`（字符串）与 `%1$d`（数字）；`%s` 仅限单占位符且与上游一致。
- 文本中的引号 / 反斜杠用 XML 转义（`&quot;`、`&lt;`、`\\`），内容中含 `<` 时用 `&lt;`。

## 4. 代码取用规则

| 场景 | 写法 |
| :--- | :--- |
| `@Composable` 内 | `stringResource(R.string.key)` |
| 带参数 | `stringResource(R.string.key, arg1, arg2)` |
| Composable 外（Toast / 回调） | `context.getString(R.string.key, ...)` |
| 无障碍描述 | `contentDescription = stringResource(R.string.cd_xxx)` |

禁止：

- 在 `stringResource` / `getString` 中做中英文拼接或三元选择文案。
- 把动态值（模型名、错误文本）拼进硬编码字符串，必须用占位符。
- 用同一个 key 承载不同语义（如「下载」既当名词又当动词）——需要时拆分为不同 key。

## 5. 验收

- 新增 / 修改文案后运行 `:app:assembleDebug`，确保资源合并通过。
- 中文设备与英文设备各验证一遍核心页面（下载首页、配置对话框、API 服务页、设置页）。
- 语言切换链路验证：设置页切 简体中文 ↔ English → 首页/设置页文案立即切换、
  无白屏闪烁、无残留 ActionBar、重启进程后保持所选语言。
- 新增 `values-zh-rCN` 条目时，用脚本核对 key 均存在于 `values/strings.xml`。
- 引入新术语时同步更新第 2 节清单。

## 维护

- 本文档由项目维护者维护；新增语言、变更资源目录结构、增删专有名词清单时更新。
- 与之同步的代码 / 文档：`app/src/main/res/values/strings.xml`、
  `app/src/main/res/values-zh-rCN/strings.xml`、
  `app/src/main/java/com/google/ai/edge/gallery/data/AppLocale.kt`、
  `GalleryApplication.kt`、`MainActivity.kt`、`docs/DOCUMENTATION_STANDARDS.md`。
