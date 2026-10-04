# SPEC-vue3-agent-console：Python Harness 运营助手接入

## 目标与边界

将运营工作台的“活动助手”从旧 Java 进程内会话 API 切换到独立 Python Harness API。用户可创建和恢复持久会话，提交只读活动查询，并看到运行状态。只有 OPERATOR 可进入页面；Python 服务仍逐请求委托 Java 校验 Bearer 身份，且所有会话/运行均按服务端用户 ID 隔离。

保留现有 Vue 3 页面、路由、登录态和 PrizeFlux 视觉系统。此次不删除旧 Java AI 服务或其 API（它们仍是迁移基线/其他调用方），也不改活动开奖与权限规则。会话侧栏只在 `sessionStorage` 保存最多 20 个会话 ID、展示顺序和每个会话最近的 run ID；不保存用户输入、答案、访问令牌或模型密钥。服务端会话事实以 Python Agent MySQL 为准。

## 用户可见行为

- 新建/打开/删除会话；页面重载后恢复最近会话，并从服务端读取历史。
- 发送问题得到 202 后展示排队/运行态，使用带当前 Bearer 的 SSE 订阅事件；断线重连只读取事件，不会再次调用模型。
- 若已受理的 run 在页面/服务中断后仍是 `ACCEPTED`，提供明确的“继续执行”按钮，点击只对原 run 发起恢复，不创建第二个问题。服务端数据库 claim 保证多次点击/并发实例最多一个 worker 执行。
- `RUNNING` 显示进行中；`COMPLETED` 展示答案及从服务端加载的对话历史；`FAILED`、`CANCELLED`、`INTERRUPTED` 显示中文状态和安全提示。
- `INTERRUPTED` 不提供自动恢复/重跑：说明上次调用可能已经开始或产生费用，用户需重新编辑问题并显式发送新一轮。
- 删除活动会话收到 202 时立即从本机列表隐藏并提示服务端正在取消运行后清理；204 时正常移除。用户可在页面离开前继续操作其他会话。
- 展示服务端报告的模型供应商类型（仅 `fake` / `openai-compatible`，不暴露密钥、URL 或模型配置细节）；外部模型模式提示可能产生费用。读取失败时采用中性文案，不猜测模型。

## API 与错误处理

- Python Agent API 经 Vite `/agent-api` 代理到本机 8083，并剥离代理前缀；独立 `web/src/api/agent.ts` 处理其普通 JSON / SSE 协议，不复用 Java `PFX-0000` envelope。
- 使用既有契约：`POST /api/ai/v2/sessions`、`GET /sessions/{id}`、`POST /sessions/{id}/runs`、`GET /runs/{id}`、`GET /runs/{id}/events`、`POST /runs/{id}/cancel`、`DELETE /sessions/{id}`。新增 owner-scoped `POST /runs/{id}/resume` 仅能 dispatch 已受理 `ACCEPTED` run；其他状态返回 409，不创建新 run。
- 前端遇到 401 使用现有全局登录失效处理；403、404、409、422、503 映射为不泄漏内部堆栈的说明。会话/运行 ID 对当前用户不可见时均按不存在处理。
- readiness 响应增加安全的 `model_provider` 标识，仅用于 UI 边界提示，不暴露任何凭据。

## 结构与实现约定

- `web/src/api/agent.ts`：Python API transport、类型、SSE reader；`web/src/api/client.ts` 只导出共享 401 通知点。
- `web/src/views/OperatorAiView.vue`：会话、历史、发送、运行状态、继续 ACCEPTED、删除的交互与状态转换。
- `web/src/styles/main.css`：在现有色板、字体与留白体系内添加状态和响应式样式；支持键盘焦点、语义化按钮/提示和减少动态效果。
- `web/vite.config.ts`：Agent API 本地代理。
- `agent-service/src/prizeflux_agent/api/agent_routes.py` 与 `api/schemas.py`：安全恢复端点；`api/health.py`：安全 provider 标识。
- 按 `controller/API → application → domain` 约束扩展；不把会话 owner 身份或授权交由浏览器决定。

## 验收与验证

- Python：恢复只作用于本人 run；不存在/他人 run 隐藏为 404；仅 ACCEPTED 可恢复；其他状态稳定返回冲突；并发恢复不产生重复模型执行。健康响应 provider 取当前配置且无秘密字段。
- Web：重载后恢复 ID 并拉取服务端历史；发送只请求一次；重连不提交问题；ACCEPTED 显示显式恢复按钮；RUNNING/终态映射正确；中断不自动重跑；删除 202/204 均反馈正确；401 触发既有退出处理；代理和构建通过。
- 按项目批次约定运行 Agent 单元/ASGI 测试、Web 定向 Vitest 与构建、Ruff/mypy；阶段末再执行 MySQL 集成回归。外部模型、Java/Python 真实进程 smoke 和公网容量未实测前必须标为未验证。

## 不在本规格中的事项

不实现 RAG、对话摘要/长期记忆、真实模型密钥配置、开放注册、Agent 写工具、自动重试付费请求、生产部署或压力容量承诺。不得声称当前用户已掌握相关代码。
