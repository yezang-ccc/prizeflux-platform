# Spec: `creator-agent-copilot`

状态：早期短请求 Java Fake Harness 原型规格。长期运行 Creator Copilot 的当前接口/持久化/报告合同以 [`SPEC-agent-creator-run-context.md`](SPEC-agent-creator-run-context.md) 为准；本文件只保留现有 Java 端点和旧前端流程的兼容边界，不能据此把 Java 原型称为 Python Agent 完成。

## Objective

让 Agent 在创建活动时产生可见、可评测的实际帮助：对结构化草稿执行有限的 Harness 工具循环，调用确定性草案校验并解释缺项/风险，给出可供用户审阅的建议。它不只是普通聊天窗，也不能自行写入活动或改变权限。

## Contract and Structure

- USER 创建页提供“检查草案”动作，提交当前表单字段到 AI HTTP；服务端每次向 Campaign 验证 Bearer 身份和 USER 角色。API 返回 `simulated=true`、结构化校验结果、说明文本和不含隐私值的 Harness 步骤轨迹。
- HTTP 入口为 `POST /api/ai/creator/validate`；AI 服务通过固定 Campaign 校验客户端调用 `POST /api/me/campaigns/validate`，此工具只返回规则校验，不创建活动、不保存草稿。
- Fake Model 通过类型化只读工具 `campaign.draft.validate` 请求验证结构：日期窗口、每日次数、奖品数量/名称/库存/概率和值域/概率总和。校验逻辑仍由 Java 业务代码执行，模型不能替代。
- Agent 仅返回建议；创建者必须自己预览并明确点击 Campaign 的“保存草稿”接口。无 Agent 写活动、提交审核、批准、发布、开奖或库存工具。
- AI 服务不持有 Campaign DB 连接；认证重新调用 Campaign 身份接口。会话若持久化后续另做保留/隐私决策；此切片只使用现有边界或无状态单轮调用。
- 页面明确标注 Fake Model / 模拟建议；不可声称自然语言理解、真实模型质量或可自动合规。

## Tech, Testing, Boundaries and Success Criteria

沿用 `prizeflux-ai` Java 17 框架无关 `AgentRunner`、`ToolDefinition`、`AuthorizedToolExecutor` 与 `ModelPort`；Campaign 提供可复用的确定性配置校验契约。执行 `backend/.\mvnw.cmd -pl prizeflux-ai -am test`，新增工具授权/参数边界/流程轨迹测试；前端 Vitest 验证调用和建议需显式保存。

- Always：工具最小权限、服务端重新校验 JWT/角色、输出限长、模型/AI 不做确定性抽奖决策。
- Ask first：真实模型 Provider/API Key/费用、会话持久化/多实例、Agent 写工具/自动提交/发布。
- Never：信任模型给的 userId/owner/role/中奖结论，自动提交或审核活动，把 Fake Model 说成真实模型。
- Success：USER 可得到验证过的结构化建议与可读轨迹；工具范围外操作被拒；最终草稿只有显式业务 API 保存。
