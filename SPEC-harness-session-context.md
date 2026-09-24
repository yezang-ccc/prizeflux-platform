# Spec: harness-session-context（首批：用户隔离的多轮会话与上下文窗口）

状态：2026-09-24。此切片沿用聚焦版 `harness-evidence`；根据已批准的项目范围和用户持续建设授权直接实施。默认边界：AI HTTP 继续只允许 OPERATOR；首批 SessionStore 是单进程内存实现，不连接 Campaign/MySQL/Redis，也不把用户对话写到磁盘。

## Objective

让一个已鉴权 OPERATOR 能创建会话、跨 HTTP 请求继续对话、查看和删除自己的会话。每次调用仍通过 Campaign `/api/auth/me` 重新验证 JWT；会话的唯一归属是服务端身份 `userId`，绝不接受客户端声明的 owner。线程历史是用于审计/回放的追加式记录；模型上下文是从历史派生的、长度受限的视图，两者不可混为一份会被压缩覆盖的数据。

首批使用内存适配器：数据仅在该 AI 进程生命周期内存在，服务重启或实例切换后会话丢失；限制单用户与全进程会话数、每会话消息数与字符数，并设置空闲 TTL。上下文窗口按消息字符数（不是模型 token 数）取最近完整交互，当前请求必须完整保留；超长当前交互返回固定错误，不静默截断或伪造摘要。历史过旧而被窗口排除时保留在会话记录中，但不参加本轮 Fake Model 输入。

## Research / Design Decisions

复核了官方高 Star 参考项目，详细设计取舍见 [Harness 参考笔记](docs/architecture/harness-reference-notes.md)。采用：mini-swe-agent 的线性追加轨迹；OpenHands 的事件日志与派生会话视图分离；DeepAgents 对运行检查点与长期文件/记忆不同职责的区分。当前不引入 LangGraph、通用虚拟文件系统、分支事件树、长期记忆或真实模型压缩。

## Tech Stack / Commands

Java 17、现有 Spring Boot 3.5 `prizeflux-ai` 与 JUnit 5；不引入 Agent 框架或数据库依赖。

```powershell
cd backend
.\mvnw.cmd -pl prizeflux-ai -am '-Dtest=SessionStoreTest,ContextWindowBuilderTest,AiSessionControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -pl prizeflux-ai -am test
```

改动涉及跨 HTTP 身份/会话边界；该切片结束时再进行一次全后端回归。

## Public HTTP Contract

- `POST /api/ai/sessions`：要求 Bearer JWT + Campaign 验证的 OPERATOR；服务器生成 UUID 会话 ID，返回 ID、版本、创建时间。
- `POST /api/ai/sessions/{sessionId}/messages`：JSON `{ "campaignId": 123, "question": "...", "expectedVersion": 0 }`；服务端验证身份、拥有权与版本，构造受限窗口，用固定只读 Campaign 工具执行 Fake Model，然后仅追加本轮新事件。成功响应返回会话 ID/新版本及现有安全响应 DTO。
- `GET /api/ai/sessions/{sessionId}`：同样先向 Campaign 验证身份；仅所有者可读取，返回有限且不含 token 的消息视图。会话不存在、已过期或非所有者统一 404，避免枚举会话。
- `DELETE /api/ai/sessions/{sessionId}`：仅所有者可删；重复删除返回 404。
- 既有无状态 `POST /api/ai/campaign-summary` 保持兼容。

同一会话并发写以 `expectedVersion` 做乐观冲突检测；版本不匹配返回 409。每个消息请求都重新校验 JWT；会话中不保存 Bearer token、凭据或 Campaign 签名密钥。Session API 错误只给固定状态/错误码，不回传上游内容。

## Project Structure

```text
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/session/  会话 ID、不可变快照、SessionStore 端口
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/infrastructure/session/  进程内存 store
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/context/  完整交互分组和字符预算窗口
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/api/  Session HTTP DTO/controller
backend/prizeflux-ai/src/test/java/com/prizeflux/ai/  存储、上下文、HTTP 权限与冲突测试
```

## Code Style

会话快照和事件不可变；追加要求调用者提交读取时的版本。模型输入由独立窗口构造器派生，不通过删历史来腾预算。

```java
ConversationSnapshot current = sessions.findOwned(sessionId, actor.userId(), now);
ContextWindow window = windows.build(current.events(), newUserMessage);
// 执行成功后使用 current.version() 作 CAS 追加，避免并发覆盖。
sessions.appendOwned(sessionId, actor.userId(), current.version(), newEvents, now);
```

## Testing Strategy

- 单测：随机 ID/快照不变式、owner 校验、版本递增/CAS 冲突、消息与字符上限、TTL 过期清理、创建配额。
- 上下文单测：只按完整 USER 交互裁剪，最新交互优先；工具请求与观察不拆开；超长最新问题拒绝；历史事件仍原样保留。
- HTTP 集成：未认证 401、USER 403、所有者访问成功、不同 OPERATOR/伪造路径 ID 返回 404、重复删除/并发版本冲突、旧的 stateless endpoint 不回归。
- Campaign 客户端用本地 HTTP stub 验证每个 session endpoint 都重新携带用户 Bearer token 调 `/me`；工具仅为固定 GET。

## Boundaries

- Always：身份取自每次服务端 JWT 校验；用户间绝不共享 session；不存 token；事件历史不被上下文窗口覆盖；显示并测试字符预算与版本语义；Fake Model 响应始终带 `simulated=true`。
- Ask first：数据库/Redis/云端持久化、多实例共享、真实模型费用、长期记忆、自动摘要、公开会话 API。
- Never：客户端可指定 owner、把 JWT 存进会话、上下文溢出时静默丢掉最新问题、将内存会话说成重启后可恢复或分布式会话。

## Success Criteria

1. OPERATOR 能创建会话并在后续请求继续；结果真实包含此前窗口保留的历史，并执行只读活动查询。
2. 会话 ID 随机、owner 隔离；伪造 owner 或访问他人 ID 不泄露该会话是否存在。
3. 每轮按字符/消息硬上限构造完整交互窗口；老事件仍留在原会话历史，窗口裁剪不做语义摘要。
4. 进程内限制/TTL/版本冲突有自动化测试；所有 session API 都重新验证上游身份。
5. 文档明确首批会话在重启/跨实例后不保留；不宣称持久化、模型效果或多实例可用。

## Open Questions

后续是否需要 durable store、事件归档、摘要模型和跨实例一致性，留到真实模型与运行规模明确后再定；首批不预埋不可验证的分布式承诺。
