# SPEC-agent-knowledge-index：受控知识语料与版本化索引（P9）

## 目标

给 Python Agent 建立可重复、可回滚的离线知识索引构建链：固定 manifest → 安全读取项目自维护 Markdown/TXT → 哈希版本 → 按标题/段落切片 → MySQL 权威元数据 → BM25 snapshot 与 Qdrant version collection → 校验成功后原子切换 active index。P9 只构建索引，不在本任务中将检索接入模型回答（P10），也不宣称召回质量达标。

## 首版语料与权限

- 仅允许仓库 `agent-service/knowledge/` 下由项目维护的 UTF-8 Markdown/TXT；manifest 明确列举文件，不递归扫描、不抓 URL、不接受用户上传，不索引私人活动/参与记录。
- 提供两份初始语料：公开活动帮助（`PUBLIC`）和运营操作手册（`OPERATOR`）。权限由受信 manifest 指定，用户/模型不能提交 scope。
- 校验每个路径解析后仍位于 knowledge 根目录内；拒绝绝对路径、`..`、符号链接逃逸、重复 document ID、未知字段/权限、超长文件、空白文档及不支持扩展名。

## 文档身份、切片与版本

- 文档版本由规范化 UTF-8 内容 SHA-256 确定；完整 build version 由 manifest、文档 hash、chunker/tokenizer 版本、EmbeddingPort 模型名/revision/dimension 与索引配置确定。同一输入重跑幂等。
- Markdown 按标题层级与段落先切分，再以有界的估算 token 计数装箱；默认目标约 350、上限 600、重叠约 50（可测试配置），保留来源路径、标题、section/anchor、document ID/version、访问级别、ordinal 和内容 hash。
- chunk ID 由稳定输入字段推导；内容变化产生新 doc/index version，不覆写已经 READY/ACTIVE 的旧产物。估算 token 只用于切片，不称作模型 tokenizer 的精确 token 数。
- 中文 BM25 tokenizer 固定为 Unicode 规范化、数字/拉丁标识符保留和汉字 bigram；RRF/ACL 检索与引用校验在 P10 实现。

## 存储与发布

- Agent MySQL 是权威元数据：新增 index-version、document-version 与 chunk 元数据表/迁移；scope/version/状态字段均可由 MySQL 复核。Agent DB 仍与 Campaign 表隔离。
- 每个索引版本有独立 BM25 artifact 和 Qdrant collection。EmbeddingPort 必须声明 model/revision/dimension 并对批次输出校验；测试 HashEmbedding 明确仅用于管道测试，不可用于召回质量报告或生产 build。
- 先将版本置于 BUILDING，构建到新的 artifact/collection 并校验 chunk 数、向量数量/维度、索引 hash；所有工件成功后，在一个短 MySQL 事务中将新版本标记 ACTIVE、旧版标记 RETIRED 并切换唯一 active pointer。
- 在切换前任一阶段失败：当前 active index 保持可读；新版本标记 FAILED，删除其临时 artifact/collection；若外部清理失败则留待安全清理，不删除旧索引。回滚通过事务恢复上一 READY/RETIRED 版本指针。
- Artifact 写入需临时文件 + 原子 rename、内容校验和安全根路径；禁止覆盖既有版本文件。Qdrant 使用独立按版本 collection，创建 collection、批量 upsert point 与 payload index；不把向量库返回的 payload 当作最终权限判定。

## 架构与安全边界

- `domain` 定义 Document/Chunk/IndexVersion/EmbeddingPort/IndexRepository 合同，不依赖 FastAPI、SQLAlchemy、Qdrant 或模型 SDK；adapter 放在 infrastructure。
- 离线 CLI 在人工启动时构建；默认命令使用生产 EmbeddingPort 配置，不可悄悄 fallback 到 HashEmbedding。P9 的历史边界为“未提供本地运行时”；K2 后增加固定 BGE 中文 ONNX 的本地适配器，权重仅在显式 `prepare-local-model --confirm-download` 时下载，运行时必须离线且逐文件校验 SHA-256。模型/revision/dimension 和 query/passage 输入规范见 [`K2 决策`](docs/architecture/local-embedding-k2.md)；未配置或权重不符继续安全失败。
- Qdrant URL 限定 loopback 或 HTTPS；可选本地容器叠加配置仅在 `environment=local` 且显式配置完全匹配的内部 `http://host:port` origin 时例外，不能用于生产或扩大到模型/Embedding。不随异常打印 API key/正文。索引内容仅限 manifest 白名单；AI 助手、抽奖和 Campaign 核心链路不依赖 RAG 可用性。

## 验收

- 重建相同语料得到同 version/chunk IDs，不新增重复记录；内容/权限/模型 revision 变化得到新 version。
- 路径逃逸、符号链接、非法 ACL、超限/无效 UTF-8、空文档拒绝；PUBLIC/OPERATOR 元数据从源头正确。
- chunk 边界保留标题上下文，文档不丢字，估算上限/overlap 符合配置；中文 bigram 与数字标识符 tokenization 有确定性测试。
- Fake embedding 维度/批次校验和 Qdrant HTTP contract 由 mock 验证；MySQL 8.4 验证唯一 active pointer 与事务性切换/回滚。
- 注入 Embedding、Qdrant、snapshot 与 DB 发布前失败；旧 active 版本不变化，不留下可被检索的半成品版本。
- 默认回归、MySQL 专项、Ruff、mypy、CLI help、`git diff --check` 通过。若未下载/运行真实本地 embedding 模型，召回率、延迟和真实向量语义一律标“未验证”。

## 后续（不在 P9）

P10 再加入 owner/role scope 预过滤、MySQL 权威复核、BM25+dense/RRF top-k、引用稳定 ID 与撤回即时阻断、无证据拒答和检索评测。下载 BGE 或接供应商 embedding、模型许可证与资源评测需单独核验；真实生成模型密钥/费用须用户配置并确认。P9 不实现 Qdrant 公网暴露、任意来源导入、PDF/OCR、RAG API 或提示词注入对抗基准。

## 依据

- [项目产品 Agent 架构](docs/architecture/product-agent-blueprint.md#7-rag-的必要范围)
- [Qdrant collections](https://qdrant.tech/documentation/concepts/collections/)
- [Qdrant points / upsert](https://qdrant.tech/documentation/concepts/points/)
- [Qdrant filtering](https://qdrant.tech/documentation/search/filtering/)
