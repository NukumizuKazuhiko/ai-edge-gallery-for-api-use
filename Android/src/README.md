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

## 贡献者与致谢

本项目是对以下开源项目的精简与改造，保留其 Apache 2.0 许可协议：

- **上游原版**：[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — 原始功能与架构
- **API 化改造参考**：[fangzny1/Gallery-OpenAI-API](https://github.com/fangzny1/Gallery-OpenAI-API) — 将原版改造成 OpenAI 兼容 API 服务的参考实现

在此基础上进行了界面精简、保活与体验优化，向所有上游贡献者致谢。

## License

[Apache 2.0](LICENSE)
