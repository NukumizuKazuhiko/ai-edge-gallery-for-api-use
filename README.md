# AI Edge Gallery for API Use

> **精简改造版** — 基于 [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) 精简而来，砍掉了全部聊天/对话/游戏等前端界面，应用只保留两件事：

1. **下载模型的首页** — 打开应用直接看到全部可下载的 LLM 模型，支持下载、删除、进度显示；
2. **本地 API 服务启动页** — 点击已下载模型进入启动页，一键启动 OpenAI 兼容的 HTTP 服务器，让局域网内任意 OpenAI 兼容客户端（ChatBox、Python `openai` 库、curl 等）直接调用手机上离线运行的模型。

**新增 / 保留功能：**

- 🔌 **OpenAI 兼容 API**：`POST /v1/chat/completions`，支持流式（SSE）与非流式
- 📋 **自动模型列表**：`GET /v1/models` 自动返回 App 当前已下载的全部模型
- 🔐 **Bearer Token 鉴权**：可选，设置后未带 token 的请求返回 401
- 🖼️ **图片理解**：支持多模态模型，通过 `image_url`（base64）传图
- ⏳ **请求排队**：并发请求自动排队
- 🛠️ **模型获取修复**：模型列表缓存优先加载 + HTTP 5 秒超时，断网/弱网下不卡死
- ⚡ **保活设置**：通知权限、忽略电池优化、厂商电源管理白名单，避免后台被杀
- 🌐 **下载源切换**：设置里可选择「原生直连（Hugging Face）」或「魔搭社区（ModelScope）」。切换到魔搭后，Gemma 4 E2B / E4B 模型从国内镜像 [venshell/gemma-4-it-litert-lm](https://www.modelscope.cn/models/venshell/gemma-4-it-litert-lm) 下载，无需科学上网；未镜像的模型自动回退到原生直连。
- ⚡ **多线程分片下载**：魔搭源下将文件分成 4 段并行 Range 下载，实测速度约 26 MB/s（单连接约 1.7 MB/s），大幅减少掉速波动，下载体验接近原生直连。
- 📂 **导入本地模型**：点击首页右下角 `+` 按钮可从本机导入 `.litertlm` 格式的模型文件（仅支持该格式，其他格式会被拒绝）。导入后可编辑加速器（默认 GPU）、采样参数等，模型会出现在首页「Imported models」分组，并自动接入本地 API 服务的 `/v1/models` 列表。此功能用于加载自转换 / 自收集的 LiteRT-LM 模型（例如用 [LiteRT-LM 转换工具链](https://ai.google.dev/edge/litert-lm) 从 GGUF 等格式转换出的产物）。

**文档规范：** 项目文档编写、命名、结构与维护遵循 [docs/DOCUMENTATION_STANDARDS.md](docs/DOCUMENTATION_STANDARDS.md)；多语言支持与文案管理遵循 [docs/I18N_STANDARDS.md](docs/I18N_STANDARDS.md)。

> **关于上游文档：** 本仓库仅发布 Android 应用源码。上游附带的部分本地文件
> （`CONTRIBUTING.md`、`DEVELOPMENT.md`、`ANDROID_API_README.md` 等）及本地工作区产物
> （`models/`、`skills/`、`mcp/` 等）已被 `.gitignore` 排除并统一收拢到 `local/` 目录，
> 不随仓库分发；如需查阅请访问 [上游仓库](https://github.com/google-ai-edge/gallery)。

## 致谢 / Acknowledgments

本项目是对以下开源项目的精简与改造，向所有上游开发者与贡献者致以诚挚谢意：

| 项目 | 说明 | 许可 |
| :--- | :--- | :--- |
| [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) | 原始项目，提供底层架构、模型下载/管理、LiteRT-LM 推理等核心能力 | [Apache 2.0](LICENSE) |
| [fangzny1/Gallery-OpenAI-API](https://github.com/fangzny1/Gallery-OpenAI-API) | API 化改造的参考实现，将原版改造成 OpenAI 兼容的 HTTP 服务 | [Apache 2.0](LICENSE) |

在本仓库的实现过程中，我们：

- 保留了上游全部源码文件的原始版权声明（`Copyright 2025 Google LLC` 等）与许可证头部；
- 在保留核心能力的基础上删除了聊天/对话/游戏等前端界面，仅保留「模型下载首页 + 本地 API 服务」；
- 新增了本地 OpenAI 兼容 API、保活机制、模型获取修复等能力。

## 许可证 / License

本项目基于 **Apache License 2.0** 发布，完整许可文本见 [LICENSE](LICENSE)。

### 开源合规说明

本仓库为上游项目的修改分发（derivative work），遵循 Apache License 2.0 相关条款：

- **许可副本**：本仓库随分发附带完整 [LICENSE](LICENSE)（Apache License 2.0）副本；
- **版权声明**：保留上游源码文件中的原始版权与许可声明（`Copyright 2025 Google LLC`）；
- **NOTICE**：上游项目未附带 NOTICE 文件，故无需在本仓库中保留；
- **修改声明**：本 README 与 Git 提交历史共同说明了相对上游的修改内容。

> **免责声明**：本项目与 Google LLC 无任何关联，非 Google 官方产品或服务。Google AI Edge Gallery 是其各自所有者（Google LLC）的商标/财产，本项目仅作技术研究与个人使用。
