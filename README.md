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
