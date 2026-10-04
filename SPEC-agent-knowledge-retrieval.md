# SPEC-agent-knowledge-retrieval：受权知识检索与证据合同（P10）

## 目标

从 P9 已发布的 active index 中获取可供 Agent 使用的知识证据。P10 只实现 Python 应用内检索合同与来源验证，不开放任意检索 HTTP API，不改变 Harness prompt/输出 DTO，也不连接真实 provider。

## 授权与查询

- Actor 仅由服务端身份端口产生：USER scopes 固定为 `{PUBLIC}`；OPERATOR 固定为 `{PUBLIC, OPERATOR}`。调用方和查询字符串不能指定 scope、version、collection 或 MySQL owner。
- 仅加载 MySQL active pointer 所指向且状态为 ACTIVE 的 index；BM25 artifact 必须通过数据库保存的 SHA-256 校验且 snapshot version/analyzer 必须匹配。最终 evidence SQL 在同一查询中再次要求候选 version 仍被 active pointer 指向，避免检索期间切换索引后返回旧版本内容。
- BM25 与 Qdrant dense 两路各取最多 20 个；Qdrant 在 query filter 中必须同时约束 `index_version` 与服务端 scopes，不返回 payload/vector；使用 RRF `1/(60+rank)` 稳定融合，默认前 5 个候选。
- 两路候选合并后，必须从 Agent MySQL 按 active version、candidate IDs、allowed scopes 联查 chunk 与 document-version；再次比较 chunk/document scope、内容 hash 并排除 `revoked_at` 文档。向量库 payload 与 BM25 scope 不是最终权限依据。
- 输出只有 MySQL 复核通过的 chunk：稳定 citation ID、标题、section、原文片段、文档版本 hash。没有证据返回 `NO_EVIDENCE`；索引缺失、hash 错误或 Embedding/Qdrant/MySQL 异常返回有界安全错误，不降级成无来源的模型答案。

## 文档撤回

- 增加 MySQL 权威 `revoked_at`；`revoke_document(document_id)` 同事务将该 ID 的所有已建版本标记撤回。P10 retrieval 每次查询都排除撤回文档，因此无需等待删除 Qdrant points 即可阻止原文进入 Harness。
- 新版本重新导入时产生新的 document-version 行且默认未撤回；回滚到旧版本不会自动清除撤回标志。恢复必须通过发布明确包含该文档的新 index version 完成。
- 撤回存储接口不由普通 Agent 用户调用；后续运营权限入口应单独授权和审计。

## 验收与非目标

- USER 永远不能从 BM25、Qdrant 或最终 evidence 收到 OPERATOR 片段；伪造 vector payload、过期 index、过期 hash 和 revoked document 均不能通过 MySQL 复核。
- mock contract 验证 Qdrant 搜索 filter 和不返回 payload；RRF 顺序、稳定并列、无命中与快照 checksum 有可重复测试；MySQL 8.4 验证撤回对查询立即生效。
- 不测试真实语义召回率，不宣称回答准确率；没有真实 active index 时返回不可用。不新增模型工具、HTTP endpoint、回答生成或 UI 引用组件；在 P11 集成。
