# Agent 模型适配器规格

2026-09-26 路线变更：用户要求 Agent 独立为 Python 子项目。下列 Java 适配器扩展计划暂停，现有未接入原型保留待迁移清点；新实现以 [产品架构](docs/architecture/product-agent-blueprint.md) 第 5–6 节和 [交付计划](docs/architecture/product-delivery-plan.md) P4–P8/P13 为准。Fake 默认、凭据不入库、真实调用需确认费用等约束仍有效。

状态：Java ModelPort 适配器扩展路线仍暂停，Fake 默认和密钥保护约束继续有效。当前 Python Agent 已按用户 2026-09-28 授权完成大陆 Kimi `kimi-k2.6` 单次协议探测（费用上限 ¥10）；完整 Harness/产品页面验收见 `docs/architecture/product-delivery-plan.md` 的 P13 与 `PROJECT_STATUS.md` 顶部记录。

2026-09-28 Python adapter 兼容性补充：内部只读工具名可包含点号，而 Kimi function name 应限于英文字母、数字、连字符和下划线；provider adapter 需在 HTTP 边界正规化工具名，并在响应与多轮历史消息上做可逆映射，内部 allowlist 名称与授权策略不变。测试应覆盖供应商别名、canonical name 回映及 reasoning/tool result 多轮序列化。

## 目标

让现有 Java Harness 的模型实现可由配置选择，保留当前 Fake 流程作为默认和离线测试基线；为 Kimi/DeepSeek 等 OpenAI-compatible Chat Completions 服务预留一个通用 HTTP 适配器。具体供应商、API Key 与付费调用由用户之后本机配置并明确确认。

## 接口和边界

- 适配器实现现有 ModelPort/模型端口，不侵入 AgentRunner、工具授权器和 Campaign 领域。
- `fake` 默认无需网络与密钥；`openai-compatible` 必须显式启用，并要求 base URL、模型名和非空密钥，否则启动失败且不得回退静默调用。
- Key 仅由环境变量/本机忽略配置注入；日志、异常、响应体、UI、仓库状态和测试快照不得泄露 Key 或 Authorization header。
- 将模型输入限制在当前受控 Harness transcript 与允许的只读工具结果。工具白名单、角色、参数验证由服务端执行；模型输出永远不直接执行。
- 强制连接/请求超时不超过 RunLimits 剩余预算，限制 transcript/output 大小；把网络错误转成脱敏的 Harness 错误状态，不自动重试付费请求。
- 对 Kimi 与 DeepSeek 使用共同 Chat Completions HTTP 合同，但不硬编码易过期的模型名；provider/baseURL/model 均可配置。默认仍 Fake。
- Python Harness 接 Kimi K2.6 等带推理内容的工具调用时，有界透传 assistant `reasoning_content` 到同一 run 的后续请求，并计入 context 字符预算；该字段仅保留在本轮内存 transcript，不写入会话历史、用户可见答案或审计事件。

## 费用与外部调用关口

实现、单元测试和本机 Fake 演示均不触发外网模型调用。真实调用前需用户提供本机密钥配置、选择模型/服务区域，并确认供应商计费与单次预算。密钥不通过聊天发送。

## 验收

1. 默认上下文装配 Fake；没有网络请求、没有密钥也可运行既有 AI 测试与 UI。
2. 配置错误时启动/调用清楚失败，不降级成未标记的假回答。
3. 测试验证请求映射、Bearer 头、剩余时间超时、响应解析、HTTP 非 2xx、空响应/超大输出和错误脱敏；使用 MockWebServer/MockRestServiceServer，不连接真实供应商。
4. 新模型只能返回受限 ModelAction；未经 AuthorizedToolExecutor 授权的工具调用拒绝，真实模型不能取得写工具。
5. 文档明确 Fake 与真实供应商差异、配置变量、费用闸门。
