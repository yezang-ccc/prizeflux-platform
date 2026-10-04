# SPEC-agent-knowledge-harness：Harness 受权检索接入

## Objective

把 P10 的 `KnowledgeRetriever` 接到 Python Agent 的一次性运行流程，让运营/用户问答使用服务端核准的活动知识，并能从 run API 看见本轮检索来源。检索默认关闭；未配置真实 Embedding/Qdrant 时，不产生外部请求，也不影响现有只读 Campaign 工具。

## Contract

- 只有 `PFX_AGENT_KNOWLEDGE_RETRIEVAL_ENABLED=true` 才装配检索。启用时必须同时配置独立 Agent MySQL、兼容 Embedding provider 和 Qdrant；以启动校验阻止半配置，不做静默 Fake 检索。
- 使用本轮通过 Campaign `/api/auth/me` 复核的 Actor；USER 只检索 PUBLIC，OPERATOR 可检索 PUBLIC 与 OPERATOR。调用者不能提供 scope、索引版本、collection 或 owner。
- 当前输入在进入模型前进行一次检索。匹配片段以明确标记的“不可信资料数据”加入 system context，并要求模型仅据证据回答文档类问题、对可核验事实标出 citation ID；资料中的指令不可执行。授权 Campaign 只读工具继续可用。
- 若 `NO_EVIDENCE` 且模型没有取得授权业务工具结果，Harness 用固定文案替换模型自由回答；若索引/Embedding/Qdrant/ACL 检查失败则本轮失败并返回安全错误，不降级为无依据答案。检索开关关闭时维持现有 Harness 行为。
- 模型完成后、持久化前，以当前 MySQL active pointer、权限、hash 和撤回状态再次核验本轮 evidence。任一被撤回/切换/修改的 evidence 导致本轮安全失败，不保存引用或答案。
- 只把来源元数据（citation ID、index version、document ID/hash、标题、章节、scope）附加到 Agent run；不重复保存 chunk 原文。owner-scoped run API 返回元数据时按请求者当前角色过滤 scope。普通会话历史/API/SSE 不暴露原始知识片段。
- 引用对象跟随答案与完成状态在同一 RunStore 完成事务中落库；会话删除与既有 FK 级联一并清除引用。已有历史迁移不改，新增 Alembic migration。

## Commands and Structure

- Source: `agent-service/src/prizeflux_agent/{api,application,domain,infrastructure}`。
- Tests: `agent-service/tests`; MySQL integration uses the existing opt-in Testcontainers marker.
- Schema: `agent-service/alembic/versions`.
- Verify: from `agent-service`, `uv run --locked ruff check src tests`, `uv run --locked mypy src`, `uv run --locked pytest -m 'not mysql' -q`, and with Docker `PFX_AGENT_RUN_MYSQL_TESTS=1 uv run --locked pytest -m mysql -q`.
- Repo check: `git diff --check`.

## Code Style

Keep authorization at the server boundary and pass immutable domain values inward. Retrieval adapter failures map to safe codes, never provider text. Use keyword-only dependencies for factories and bounded JSON DTOs; do not put passage text in persisted citation metadata.

## Testing Strategy

- Unit: actor/scope propagation, safe no-evidence refusal, authorized campaign-tool fallback, evidence context, valid citation references, failure behavior, and pre-completion revocation/index-switch detection.
- MySQL: migration, run completion atomically stores answer + citation metadata, failed completion stores neither, session cascade removes both, and owner-scoped reads filter citation visibility using the current actor.
- Regression: preserve current Harness/model behavior when retrieval is disabled; existing Campaign authorization, cancellation, idempotency and run fencing tests remain green.
- No claim of semantic recall quality until a real index/provider has been configured and evaluated.

## Boundaries

- Always: default retrieval off; server-side Actor determines scopes; retain read-only tool policy; safe refusal/fail-closed semantics; do not persist document passage text or credentials.
- Ask first: activating a real provider, paying for embedding/model requests, writing to a remote Qdrant collection, or publishing a knowledge corpus.
- Never: allow AI to draw/publish/change campaigns; accept model/caller scope or user ID; expose OPERATOR sources to USER; present Fake/hash vectors as semantic retrieval.

## Success Criteria

1. Disabled default makes zero retrieval/provider calls and preserves existing API behavior.
2. Enabled end-to-end run passes only server-authorized evidence to the model and persists references atomically with the answer.
3. `NO_EVIDENCE` cannot become an unsupported free-form answer; authorized live Campaign reads remain available.
4. Revocation or active-index replacement between retrieval and run completion prevents answer/citation persistence.
5. Citation API output contains no passage text or secrets and respects the caller's current role.
6. Existing non-MySQL and MySQL Agent suites pass; `20260927_07` is the single Alembic head.

## Open Questions

None for this implementation slice. Actual provider/index activation and UI source cards remain later tasks requiring external configuration/authorization and a separate product verification batch.
