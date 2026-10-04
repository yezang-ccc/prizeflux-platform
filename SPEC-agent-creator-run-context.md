# Spec: Python Creator Copilot run context and report

状态：A1 合同冻结；A3 持久输入、A4 Python Java-first 审查/原子报告与 A5 Creator 编辑页的 Python 持久 run 接入均已实现并通过自动化回归。Fake/隔离多进程运行态和真人浏览器验收未完成，因此不代表端到端 Creator Copilot 已完整验收。该规格把现有短请求 Java Fake Harness 原型与持久化 Python Harness 主线区分开。

## 1. 目标与边界

在现有 Python Agent v2 持久化 run 中增加可选的结构化活动草案检查模式。前端提交当前表单快照；Java Campaign 继续给出确定性校验，模型只解释结果并提出建议。用户自行修改、保存或提审。Python Agent 不持有 Campaign 数据库连接，不保存/审核/发布活动，不执行抽奖或库存操作。

本切片只实现 `CHAT` 与 `CREATOR_DRAFT_REVIEW`。审核驳回解释、其他人的数据读取、运营写操作、自动保存/提审、模型主导业务校验均不属于本合同。

## 2. API 合同

沿用现有 `POST /api/ai/v2/sessions/{sessionId}/runs`，不另开第二套会话/执行系统。现有 `clientMessageId`、`expectedVersion`、`input` 不变；新增可选字段：

```json
{
  "clientMessageId": "web-...",
  "expectedVersion": 2,
  "input": "请指出最需要优先处理的问题",
  "mode": "CREATOR_DRAFT_REVIEW",
  "context": {
    "schemaVersion": 1,
    "draft": {
      "name": "夏日活动",
      "startsAt": "2026-10-01T00:00:00Z",
      "endsAt": "2026-10-07T23:59:59Z",
      "dailyLimit": 1,
      "prizes": [
        { "id": 101, "name": "礼品卡", "totalStock": 20, "probability": 0.1 }
      ]
    }
  }
}
```

规则：

- `mode` 缺省等价于 `CHAT`；`CHAT` 不接受 `context`。`CREATOR_DRAFT_REVIEW` 必须携带版本为 1 的 `context`，不根据自由文本猜模式。
- context 为严格 DTO：未知字段拒绝；不得含 `userId`、owner、role、Bearer、任意 URL、提示词或模型工具参数。身份一律来自 Campaign 实时验证的 Authorization Bearer。
- 草案是用户主动提交的本次快照，不带 campaignId，也不据此读取数据库对象。该接口只验证用户提供的数据；读取已保存草案/审核理由必须另有 owner-scoped Java API 和明确合同。
- 限制沿用 Java DTO：活动名最多 100 字符；奖品 1–50 项；奖品 ID 为正 Java `long`；库存和每日次数为 1–2,147,483,647（库存可为 0）；概率为 JSON 数字且在 [0,1]。日期必须符合带时区 RFC3339，最多 6 位小数秒；禁止 NaN/Infinity。字段和值的确定性业务规则最终由 Java 验证。
- `input` 保留现有非空、最多 6,000 字符限制。整体请求体设有限长 64 KiB；超限在 admission/模型执行前拒绝。API 按 Decimal 解码 JSON 小数，拒绝重复键；指纹十进制展开有同等字节上限，避免极端指数导致内存放大。
- Context 中的名称/文案属于不可信用户数据，只作为数据传入，不得拼接进 system/developer policy。

## 3. 权限矩阵

| 身份 | CHAT | CREATOR_DRAFT_REVIEW |
|---|---|---|
| 未登录/无效或过期 Token | 401 | 401 |
| `USER` | 允许现有受限只读工具 | 允许；Java 必须用同一 Bearer 调用 `/api/me/campaigns/validate` |
| `OPERATOR` | 维持现有运营只读策略 | 403；本首版不提供运营草案预检 |

校验服务不可用、响应不合法、身份在 run 执行期间变化：fail-closed，不返回部分报告。Agent 不独立解析 JWT、不信任请求中的角色/用户 ID。日志/事件不得记录 Bearer、完整草案或供应商原始错误正文。

## 4. 规范化、指纹与幂等

- `CHAT` 且无 context 必须保留现有 `prizeflux-agent-run:v1` 指纹算法及输入规范化，确保旧客户端和升级前已受理 run 的同键重放兼容。
- Creator run 使用新的 `prizeflux-agent-run:v2` 域分隔；SHA-256 输入是 canonical JSON，包含 `expectedVersion`、trim 后 `input`、`mode`、`context.schemaVersion` 和完整草案。JSON 对象键排序、无空白；时间转换 UTC；Decimal 以普通十进制字符串去除无意义尾零，负零归一为 `0`；奖品数组保持用户顺序（顺序可影响字段定位）。不经 binary float 往返。
- 同一 session + `clientMessageId` + 相同规范化请求重放同一 run，不重新调用模型；同键但输入/context 不同返回 409。Context 必须在 admission 阶段验证、规范化和参与指纹，再持久化。
- 后端计算 `draftFingerprint`（仅规范化草案自身，不包含用户 prompt、Bearer 或数据库 ID），供报告与 UI 判断草案是否已变化；此 hash 不是授权凭据。

## 5. 权威检查与模型报告

Java 校验是程序保证的前置步骤：USER 请求成功受理后，由受控执行器使用本轮原 Bearer 对精确快照调用 Java；不能依赖模型选择工具，不能因模型建议而跳过、改写或降级其结果。模型收到草案数据和校验结果，仅生成结构化解释/建议。提示词注入内容视为数据。模型格式错误只允许本轮安全失败，不自动重试付费调用。

Creator run 完成后，现有 `GET /api/ai/v2/runs/{runId}` 在原响应上增加 nullable `creatorReport`；原 `answer`、状态、SSE 事件语义和旧 CHAT 客户端保持兼容。SSE 仍只用于状态/阶段；完成事件后按现有流程读取 run detail 获取报告。

```json
{
  "schemaVersion": 1,
  "mode": "CREATOR_DRAFT_REVIEW",
  "draftFingerprint": "sha256:<64 lowercase hex>",
  "checkedAt": "2026-09-28T10:00:00Z",
  "summary": "Java 校验发现 1 项需要修正；另有 1 条可选建议。",
  "authoritativeFindings": [
    { "code": "CAMPAIGN_RULE_VIOLATION", "field": "prizes[0].probability", "message": "由 Java Campaign 返回的原始规则说明" }
  ],
  "suggestions": [
    { "field": "name", "kind": "CLARIFICATION", "text": "模型给出的建议", "rationale": "简短理由", "evidenceRefs": [] }
  ],
  "openQuestions": []
}
```

- `authoritativeFindings` 必须由 Java 响应映射，完整保留顺序与内容；模型不能创建、删减、改字段、改严重性或宣称已通过。A2 需把现有 Java `issues: string[]` 升级为稳定 code/field/message findings，并保留旧兼容字段。
- `suggestions` 是不权威的模型意见，不能混进 Findings；`evidenceRefs` 只能引用本 run 实际成功取得的来源。首个 Creator 切片若尚无检索证据，必须为空，不伪造文档引用。
- `answer` 仍是 UI 兼容摘要，必须与结构化报告一致；不得只保存 answer 而丢报告。草案或报告 schema 校验失败时，run 失败且无部分成功答案。
- run 响应对 CHAT 返回 `creatorReport: null`（或兼容客户端可忽略的缺省）；旧记录无报告，不回填虚构报告。history 中的 answer 保持既有格式。

## 6. 持久化与报告新鲜度

- 新增 Alembic 增量迁移：`agent_run.request_context` 与 `agent_run.creator_report` 可空 JSON 列；旧记录为 NULL。Request context 与幂等指纹、用户输入同 run 原子落库；完成时 answer、报告、引用及终态在同一完成事务写入。
- context 的 owner/session 约束继承既有 run owner 与 FK；删除会话、保留清理与已有 run 一致。不得新增第二套草案副本表，不将 Bearer 放入 DB。
- 前端保存本轮提交的表单快照；字段一旦变化，当前 report 立即展示“已过期”，并要求新 run。过期报告只能作参考；Java 保存/提审仍重新校验当前草案和权限，report/hash 不能作为通过凭证。
- 请求事件只允许记录 mode、fingerprint、工具名、safe status、耗时与供应商明确返回的 usage；不得把完整 context 填入 telemetry/SSE。

## 7. 兼容与实施验收

1. 旧 CHAT 无 context 请求/重放仍保持 v1 fingerprint；Python/Java 两侧严格拒绝错误模式、额外字段、角色越权和尺寸超限。
2. 同键同参数只创建一个 run；同键异草案返回 409；数据库重启后受理的 Creator run 可恢复，且完整 context 一致。
3. Java findings 始终是模型调用前的唯一权威输入，不由模型创建/覆盖；若模型不可用、输出无效或报告校验失败，本 run 安全失败且无 answer/report 落库，不自动重试付费请求。上游 Java 拒绝/超时、Token 撤销和运行中角色变化同样不落答案/报告。
4. 完成报告、answer 与终态原子写入；失败/取消不留下部分报告；owner 隔离、会话删除和已有留存任务涵盖新增列。
5. 前端 Creator 流程正常、空/错误报告/过期状态清楚；保存和提审显式由用户操作。Fake 与真实模型分别报告，真实调用有明确预算上限。

## 8. 不在本切片中做

不变更 MySQL 抽奖事实、Redis 限流、Kafka 队列；不创建 AI 业务写工具；不接更多模型、不下载 embedding、不新建向量索引、不改变用户角色、不自动提交活动、不承诺模型建议质量或生产容量。
