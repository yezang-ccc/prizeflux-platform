# PrizeFlux Java Agent 迁移基线

日期：2026-09-26。用途：作为 Python Agent 实施前的行为合同、原型清单与迁移核对表。源码依据为 `backend/prizeflux-ai`、对应 AI/Campaign API 与 `web/` 调用点。此文档描述当前实现，不代表生产就绪或真实模型已接入。

## 当前 HTTP 合同

| 方法与路径 | 权限 | 当前行为 | 调用的权威服务 |
|---|---|---|---|
| `GET /api/health` | 匿名 | 返回 AI 进程 `UP`；不证明 Campaign、模型或其他依赖健康 | 无 |
| `POST /api/ai/campaign-summary` | `OPERATOR` | 输入 `campaignId` 与问题；固定调用只读 `campaign.summary`，返回模拟摘要及 Harness trace | 委托 Campaign `/api/auth/me` 校验 Bearer，再携原 Bearer 请求 `GET /api/campaigns/{id}` |
| `POST /api/ai/creator/validate` | `USER` | 输入尚未保存的活动草案；运行模拟 Harness 调用 `campaign.draft.validate`，返回 Campaign 规则校验结果和建议；不会保存、提交审核或发布 | 委托 Campaign `/api/auth/me`，再携原 Bearer 请求 `POST /api/me/campaigns/validate` |
| `POST /api/ai/sessions` | `OPERATOR` | 创建会话，返回 ID/version | 每个请求都委托 Campaign `/api/auth/me` |
| `POST /api/ai/sessions/{id}/messages` | `OPERATOR` 且会话 owner | 输入 `campaignId`、问题、`expectedVersion`；构造有界上下文，读取活动并尝试 CAS 追加整轮事件 | Identity 与只读 Campaign 查询 |
| `GET /api/ai/sessions/{id}` / `DELETE .../{id}` | `OPERATOR` 且会话 owner | 读取或删除本进程中的会话；不存在和非 owner 统一返回 Not Found | 每个请求都委托 Campaign `/api/auth/me` |

服务端从 Campaign 的 `/api/auth/me` 得到 `userId` / `role`，不接受 body 或自定义请求头覆盖 Actor。Campaign 身份客户端限制响应体、禁止重定向、对非本机明文 HTTP 拒绝，并设置短请求超时。会话操作进行 owner 过滤；普通用户不可访问运营摘要或运营会话。Creator validate 仅允许 `USER`，使用 Campaign 服务端 owner 校验。

常见错误映射：无/无效 Bearer 为 401；角色不符为 403；格式/上下文限制为 400；owner 不符或资源不存在为 404；版本竞争为 409；会话容量为 429；Campaign 上游失败一般转为 502；未分类异常转为统一 500。返回共享 `ApiResponse` 错误码合同。

## Harness 行为与事实边界

- 当前被 Spring 实际使用的 `AiCampaignService` 和 `AiCreatorDraftService` 都走确定性 Fake/模拟答复。运营摘要先运行固定的只读工具，再返回模拟摘要；Creator Copilot 先运行确定性校验工具，再用模拟文案解释结果。模型没有保存、审核、发布、抽奖、扣库存或任意 Campaign 请求能力。
- `AgentRunner`、预算/迭代限制、类型化工具调用、角色与参数 allowlist、固定工具注册和有界上下文是已有 Java Harness 行为。它们可作为迁移合同参考，不应被简历描述成真实模型回答质量或完整生产 Agent。
- `ModelWorkflowFactory`、`ConfiguredModelWorkflowFactory` 与 `OpenAiCompatibleModelAdapter` 源码存在，但在当前 Spring runtime 中没有 Bean 装配，也没有被两个真实 HTTP service 调用；`application.yml` 没有 provider 配置项。仓库没有该 adapter 的对应自动化测试。结论：它们是未接通、未验证的原型，不可声称当前网站可通过配置切到 Kimi/DeepSeek；本轮没有真实模型请求或费用。
- 模型原型只支持返回文本，工具选择由服务端预先固定，不接受模型扩展权限。这种边界值得保留；Python 版本需通过自己的 provider port 与 Harness 策略明确重建并测试。
- 当前没有 RAG/知识库、文档来源引用或召回评测；Campaign 业务数据只通过受权只读 API 查询。

## 会话数据与并发限制

- `SessionStore` 的运行时实现是 `InMemorySessionStore`：会话/事件不落盘、不跨进程共享；重启丢失，服务多实例时各实例视图不一致。默认策略为每 owner 最多 10 个、进程总计 1,000 个、每会话最多 100 条消息/100,000 字符、空闲 30 分钟回收。
- `ContextWindowBuilder` 最多带入 24 条历史消息、12,000 字符上下文；消息问题上限 1,000 字符。会话事件不保存 Bearer。
- 同一版本消息请求在模型/工具调用之前只做版本预检查，模型完成后才通过 `appendOwned(expectedVersion)` CAS 落盘。并发测试证明最多一个请求追加成功，但在将来接入付费模型时，两个同版本请求仍可能都先执行外部调用，再有一个以 409 失败。Python 方案需在付费/模型步骤之前占有 `active_run_id`，不可只复用末尾 CAS。
- 当前没有 AI 专用 Redis 限流、跨实例并发控制或持久运行预算。Campaign 注册/抽奖限流不等同于 Agent 运行治理。

## Python 迁移应保留与可替换内容

必须保留为业务与安全合同：

1. Campaign 继续是身份、角色、owner、活动状态与确定性规则校验的权威源；Agent 每次按已认证 Actor 调用既定 API，不信任模型提供的 userId/role/owner。
2. `OPERATOR` 活动只读摘要、`USER` 本人草案只读校验这两条权限边界；Agent 不拥有业务写工具或抽奖权。
3. 结构化草案校验结果、统一错误语义、上下文/输出预算，以及可审计的工具调用轨迹。
4. Web 与后端迁移按蓝图先在独立端口并行，验收前保留 Java AI 回退。现有 Java 会话是进程内临时数据，切换时不迁移；应向用户说明需开启新会话。

允许由 Python 替换而非双线长期维护：`backend/prizeflux-ai` 的 Spring HTTP 编排、内存 `SessionStore`、Java AgentRunner 与 Fake 工作流。它们暂时保留作对照与回退；Python 合同/权限、浏览器集成验收和切换/回退演练通过之前不得删除。尚未接入 provider 的 Java model adapter 原型不构成迁移依赖，避免为其继续扩展两套运行时。

## 验证记录

2026-09-26 在 `backend` 执行 `$env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -q -pl prizeflux-ai -am test`：AI 模块 11 个 test suites、57 项通过，0 失败、0 错误、0 跳过。测试覆盖匿名/角色拒绝、Campaign bearer 转发、Creator 只读校验、owner 隔离、会话生命周期及相同版本并发只追加一轮。它们是本地/Stub 合同测试，不验证 Kimi/DeepSeek、跨实例持久化、provider timeout/费用或生产限流。
