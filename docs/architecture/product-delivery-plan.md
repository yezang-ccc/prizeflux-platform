# PrizeFlux 产品交付与面试证据计划

2026-09-28 执行补充：[增强方案](competitiveness-enhancement-plan.md) 与 [增强清单](../../tasks/competitiveness-enhancement-tasks.md) 细化本计划未完成的 P6/P8–P15。保留原任务身份和已有证据；新增工作尚未实施。历史条目中的 Docker/产品卷等旧环境描述不能覆盖 `PROJECT_STATUS.md` 最新记录。

日期：2026-09-26。目标架构见 [product-agent-blueprint.md](product-agent-blueprint.md)。以下复选框表示本轮计划验收状态，不抹除已有 Java/Vue 功能。M0 队列自动化测试与单 Broker 停机恢复已验证；多消费者 rebalance、完整本机服务 smoke 与压测仍未验证。用户已授权按确认架构实施，不重复索取逐页批准。

## 阶段与退出条件

| 阶段 | 工作及交付物 | 验收关口 |
|---|---|---|
| M0 当前工作收口 | 审核已有队列/Web 初稿；补齐失败分类、租约恢复、分区策略和运行配置 | 编译/定向测试通过；保存当前实际基线，迁移与旧功能可回归 |
| M1 Python 最小业务闭环 | foundation/runtime/campaign-tools；迁移现有 Fake 与权限用例；活动草案检查报告 | 同角色/owner/错误语义；Mock provider 合同；Java 与 Python 可独立启动 |
| M2 Agent 工程化 | 独立 schema、会话/运行幂等、前置 CAS、持久事件、SSE、取消/中断、预算与 Python 运营助手接入 | 两实例争抢只产生一个 active run；断线只续读；仅显式恢复 ACCEPTED 原 run；重启会话可读、运行有终态 |
| M3 有依据的 RAG | 受控语料、索引版本、BM25+dense/RRF、引用、访问过滤、评测集 | 有真实 Embedding 召回证据；撤回文档和角色边界测试；无资料拒答 |
| M4 产品集成与真实模型验收 | Vue 报告/引用/进度，Kimi 或 DeepSeek 选择一个先验收，保留 Fake | 用户确认本机 key/费用后跑真实端到端评测；代码、数据和截图可复现 |
| M5 发布候选 | 并发/故障/恢复实验、容器构建、技术说明和简历证据包 | 新环境可复现演示；容量结论有原始记录；公布已知限制与版本 |

达到 M4+M5 后才将 Python Agent 作为完整项目经历描述；M1/M2 阶段可以如实描述已经完成的部分。高并发业务主线和 Agent 主线分别验收，AI 完成度不影响已有 Java 能力的证据。

## 可执行任务清单

- [x] P0 记录现有 AI API、角色矩阵与 Java 行为基线；列出原型/未验证文件，确定 legacy 保留清单。见 [java-agent-baseline.md](java-agent-baseline.md)；AI 模块 57 项测试通过。provider adapter 未接线且未验证已明确标注。
- [x] P1 审核队列初稿，补齐租约/fencing、重试/人工处理、结果优先核验、扫描恢复；新增 V6 迁移。验证：重复任务、租约过期与结果优先恢复用例通过。
- [ ] P2 已实现请求 ID 分区 key、独立 draw-executor 配置及明确 ACK/DLT 策略；发奖 Outbox→Kafka→Worker 和排队 DrawQueued Outbox→consumer 的单节点 Broker 停机恢复于 2026-09-27 在隔离环境通过（证据分别见 `docs/baseline/kafka-outage-recovery-2026-09-27.json`、`docs/baseline/draw-queue-kafka-recovery-final-2026-09-27.json`）。排队演练在全新 consumer group 启动前先确认 Kafka 已存有通知、MySQL 队列仍 QUEUED，并验证 `auto-offset-reset=earliest` 消费积压。真实双 Campaign consumer group rebalance 亦已通过：12 分区 6/6、generation 2→3；单活动 24 个唯一 key 在延迟 DB 扫描时全部完成，原始证据见 `docs/baseline/draw-queue-kafka-rebalance-2026-09-27.json`。队列应用/consumer 集成测试 12 项通过；同活动并发容量、多活动混合压力与多 Broker HA 仍待实测，P2 不标完成。
- [x] P3 完成队列页面、查询限流、准入水位/积压监测。验证：前端保留 request key/手动刷新、关闭后排队拒绝、Redis 准入与即时模式回归通过。
- [x] P4 建 `agent-service/`、uv 锁、FastAPI health/config、Python 端口与严格 DTO。已在 Python 3.14.6 离线启动验收 health；13 项测试、Ruff、mypy 均通过。
- [x] P5 实现 Fake/HTTP provider 合同、LangGraph 节点与 Harness 限制。验证：未知工具/非法参数拒绝、超时、循环与模型/工具/上下文/输出预算、供应商失败脱敏；Fake 默认。
- [x] P6 实现 Campaign identity/read/validate 客户端、每运行只读工具 allowlist 与校验报告 schema。验证：Java MockMvc 集成合同、Python HTTP MockTransport、USER/OPERATOR 工具范围、owner 404 隔离、精确 Decimal JSON 与无写入。
- [x] P7 Agent 专属 schema/Alembic、owner-scoped RunStore、按轮次构造有界上下文、前置会话占有、幂等键/指纹与执行 fencing；首版 MySQL 保存会话，不依赖 Redis 记忆。验证：临时 MySQL 8.4 上两进程同键竞争只产生一个 run；同键异参冲突；过期旧执行者不能完成；重建 Store 后从 MySQL owner-scoped 读取历史，未配置 Redis。
- [ ] P8（代码/自动化与隔离真实进程 smoke 已验收；浏览器验收待复核）已接入 Campaign Bearer 核验、owner-scoped session/history/run/cancel/status API、有限并发 worker、持久取消协作、过期 RUNNING → INTERRUPTED 周期回收、SSE `Last-Event-ID` replay、本人会话删除、默认 30 天留存清理与 Vue 运营助手。新增 owner-scoped 显式恢复，仅对仍为 ACCEPTED 的原 run dispatch；RUNNING/终态不重放。长任务通过 Campaign 周期复核原 Bearer，并在持久化前二次复核；撤销/过期/身份变化/认证依赖故障均 fail-closed。P14-C 另以真实 Campaign/Python/Vue 进程验证了 USER identity、JWT TTL 到期、owner 隔离、Fake run/MySQL history/SSE。浏览器手工回归仍待复核；不得公开部署。
- [ ] P9（构建链与自动化验收已完成；真实索引构建待用户配置/确认）语料 manifest、来源/版本/ACL、切片、离线 validate/build CLI、EmbeddingPort、OpenAI-compatible embedding 和 Qdrant/BM25 adapter、MySQL 原子 active pointer。未连接真实 embedding 或 Qdrant，不能标记为已发布索引。
- [ ] P10（检索内核代码与容器验收完成；真实索引待启用）角色 scope 预过滤、BM25+dense RRF、MySQL active version + scope/hash/revocation 权威复核、citation 值对象。暂无真实 active index；无真实召回/引用质量数据。
- [ ] P11（部分完成）Python run 已有有证据上下文、无证据拒答、完成前复核和 metadata API；Vue 运营助手来源清单已接入。Creator 工作流/跨账号浏览器验收、真实索引和端到端验证仍待完成。
- [x] P12 离线评测集和 provider 合同 CI；真实生成评测单独 opt-in。静态评测通过，远程 GitHub workflow 尚未运行。
- [ ] P13 一个真实 provider 小额联调与费用/延迟记录（部分完成）：大陆 Kimi `kimi-k2.6` 配置、Key（本机 ignored `.env.product`；未读取/打印）与 ¥10 授权已落实。用户产品页面原请求报 `model_rejected`，该 code 对所有 provider 4xx 不区分；排查发现域内 function name 带点号，存在不符合 Kimi 官方命名约束的风险。adapter 已增加 provider-safe 下划线别名并在响应/历史消息中映射回内部 allowlist 名称。定向 9 tests、Ruff、Agent image rebuild/readiness 通过。生产 ModelPort 真实多轮 smoke 共 2 请求：首轮返回活动摘要工具 call，模拟 not-found 结果后第二轮最终答复成功；usage 363/104 tokens，估算约 ¥0.0052；没有调用真实 Campaign Java 工具。产品 UI 端到端仍待用户以真实活动 ID 手动提交验证；当前检索关闭。详情见 `PROJECT_STATUS.md`；不能把 adapter 多轮 smoke 写成完整产品验收。
- [ ] P14 Compose profiles、Python 接管/回退、进程资源、部署说明与故障 runbook。2026-09-27 P14-A/B/C/D 已完成：配置预检、Agent DB/schema readiness、Campaign/Worker/Python Agent/legacy AI/Vue 隔离多进程 smoke、独立迁移/运行账号、权限拒绝、JWT 到期、Creator Fake 只读工具，以及 Python Agent 本进程有界准入/每 owner 上限/满载不落库。P14-E1/E2 已添加完整产品 Compose、Java/Python/Web 镜像定义、内部 schema 与迁移/运行账号、Agent 一次性迁移任务、loopback Nginx 三路由/SSE、本地密钥及运营账号初始化流程；镜像 build 因 Docker Hub OAuth token 连接超时未完成，产品栈容器联调未验收。P14-F 已验证发奖与排队 Outbox 链路的单节点 Kafka 停机恢复、双 Campaign consumer group rebalance（12 分区 6/6，24 个单活动队列请求全部完成）、停止 Python Agent/legacy Java AI 后即时与 Kafka 排队抽奖成功，以及三套真实迁移 schema 的逻辑备份恢复到新标签 named volume。新增产品 MySQL 在线逻辑备份工具，按目标容器/卷身份和 InnoDB 检查后生成明文 SQL；已在随机临时源卷导出并恢复至新卷验证。机器上尚无 `prizeflux_product_mysql_data`，故真实产品卷恢复、加密/留存、异地副本和 PITR 仍未验收。细节见[本地部署规格](../../SPEC-local-runtime-deployment.md)与[本地运行手册](../../deploy/container-local.md)。Agent 多实例全局准入仍未实现。不得遗漏 legacy Creator 或将 JWT 重验称为账号即时撤销。
- [ ] P15（进行中）分级压测和最终证据整理。即时抽奖与异步队列各完成 100/20、250/50 两档三轮，排队另完成 500/100 三轮；三档队列吞吐中位数为 63.68/90.97/94.18 次受理/秒，p95 为 157.56/150.48/142.63ms。另有 500/100、20 个合成用户、单活动的一次补充轮（500/500 受理，每用户25次，Worker 250条成功，吞吐95.13次/秒、p95 145.25ms），以及 100/20、10 用户、5 活动的一次补充轮（99/100受理，1次 `MYSQL_CONNECTION_POOL_PRESSURE`，队列/结果/Outbox 99条、Worker 50条成功）。两份均为单轮/单consumer样本，不并入历史重复轮统计。原三轮 500/100 共受理 1,498/1,500，原因未捕获；新增审计轮 427/500 受理、73 个 PFX-9001/503 均由 `MYSQL_CONNECTION_POOL_PRESSURE` 触发，且所有已受理任务对账闭合。backlog=5 原场景观察到 5/100 受理；带原因审计的重跑观察到 9/100 受理、91 个拒绝均为 `MYSQL_BACKLOG_LIMIT`，9 条完成。非原子快照阈值是 best-effort shedding，不是硬上限。机器配置、原始 JSON、失败审计配置修正记录及限制见 `docs/baseline/README.md`。下一步优先完成 P14-E 产品 Compose 容器验收，必要时增加多活动重复采样；本机结果不外推为生产容量。

P15 状态更新（2026-09-28）：三轮基准仍按既定输入单独汇总；新增 500/100 来源审计轮中 73 个 503 均明确为 MySQL 连接池水位保护，427 个受理任务全部完成；backlog=5 来源审计轮中 91 个 503 均明确为 backlog 阈值拒绝，但实际受理 9 条、启动时 backlog=9，说明快照阈值不是并发硬上限。第一次审计尝试因奖品库存设为 50、实际仅 6 条受理而不满足履约数量断言，未写入证据；修正为库存 5 后审计通过。服务端保留通用 PFX-9001/503，只在日志和本机证据记录稳定原因枚举。完整对账与边界见 [`docs/baseline/README.md`](../baseline/README.md)。下一步转向多用户/多活动或多 consumer 与产品 Compose 运行态验收；本机数据不代表生产容量。

## 关键不变量与故障验收

### P9 知识索引构建（2026-09-27）

- 新增 [`SPEC-agent-knowledge-index.md`](../../SPEC-agent-knowledge-index.md)：只读仓库维护的 UTF-8 Markdown/TXT manifest，不递归扫描、不抓 URL、不接收上传；公开帮助与运营手册由可信 manifest 分级。拒绝目录逃逸、绝对路径、符号链接逃逸、重复 JSON 键/ID、未知字段/权限、超长/空文档和不支持扩展名。语料总量及文档数有界。
- P9 builder 用 canonical source fingerprint + chunker/BM25 版本 + embedding model/revision label/dimension 生成 64 位 index version；标题/章节上下文下确定性切片，默认约 350/600/50 estimated tokens；中文 BM25 snapshot 与 `.data/knowledge/bm25/<version>.json` 原子 artifact；Qdrant 逐版本 collection 只持有检索 metadata，不写正文。MySQL `20260927_05` 保存 index/document/chunk 与单例 pointer；P10 的最终 scope/active/hash/revocation 复核已由 Agent MySQL adapter 实现。
- `knowledge_cli validate` 无网络/DB 操作；`build` 需要显式 `--confirm-external-calls-and-writes`、Agent MySQL、Qdrant 与固定版本的 OpenAI-compatible embedding 配置。尚未运行 build、产生真实向量或触发费用。Revision 是部署者声明标签，无法保证第三方服务一定固定该修订。
- 使用仓库 `.venv` 同步环境执行 `uv run --locked ruff check src tests`、`uv run --locked mypy src`（47 source files）通过；`uv run --locked pytest -m 'not mysql' -q` 为 72 passed / 13 deselected；`python -m prizeflux_agent.knowledge_cli --help` 和 `... validate` 通过，当前 manifest 为 2 docs / 12 chunks。MockTransport 覆盖 embedding response 排序/维度/错误脱敏与 Qdrant create/index/upsert/count/delete 以及 payload 不含原文。
- `$env:PFX_AGENT_RUN_MYSQL_TESTS='1'; uv run --locked pytest -m mysql -q`：临时 MySQL 8.4 Testcontainers 13 passed / 62 deselected；包括 `20260927_05` migration schema 和 active pointer 从旧版切换到新版本、旧版退役。过程中修复 MySQL 不允许 CHECK 引用自增列，以及 document/chunk 外键插入顺序问题。MySQL 构建发布前异常保留旧指针目前由 memory-store pipeline 测试覆盖，MySQL commit fault-injection rollback 未验证。
- 当前仍无真实 active index；没有连接本机演示数据库、真实 provider 或真实 Qdrant。P9 真实构建与 P10 语义质量/召回评测未完成；仅完成代码及隔离容器测试。

### P10 受权检索内核与撤回复核（2026-09-27）

- 新增 [`SPEC-agent-knowledge-retrieval.md`](../../SPEC-agent-knowledge-retrieval.md)。应用层只依赖 retrieval ports；Actor.role 固定用户 scope（USER 仅 PUBLIC，OPERATOR 含 OPERATOR），BM25 与 Qdrant dense 各取 top 20 后以 RRF `1/(60+rank)` 稳定融合。Qdrant 查询通过当前官方 `POST /collections/{collection}/points/query`，`filter.must` 强制 active `index_version` 与 role scopes，并禁用 payload/vector 返回；对应 [Qdrant Query points](https://api.qdrant.tech/v-1-18-x/api-reference/search/query-points) 与 [Filtering / Match Any](https://qdrant.tech/documentation/search/filtering/) 文档。候选再由 Agent MySQL 按 active version、ID、scope、chunk/document 同级 scope、content hash 与 `revoked_at IS NULL` 复核；只有权威 chunk text 可以成为 citation，向量/BM25 metadata 不作为授权事实。
- Alembic `20260927_05` 持久 BM25 analyzer version；`20260927_06` 增 `revoked_at` 和检索索引。`revoke_document` 一次事务撤回同 ID 所有索引版本，退役向量无须同步删才能被 evidence 层拒绝；内核无候选返回 `NO_EVIDENCE`，索引或依赖故障返回内部安全错误，不伪装成无依据自然语言回答。
- 顺手把应用层 `RunExecutor` 从直接 import LangGraph/Campaign tool adapter 改为注入 `AgentRuntimeFactoryPort`；新的 layer-boundary 测试禁止 domain/application 依赖 infrastructure，API route 及用户流程合同不变。
- `uv run --locked ruff check src tests`、`uv run --locked mypy src`（50 source files）通过；非 MySQL 测试 78 passed / 14 deselected。MySQL 8.4 Testcontainers 14 passed / 78 deselected，新增真实 DB 数据撤回后 `get_authorized_chunks` 立即无结果。Qdrant query mock 验证 version/scope filter 及不返回 payload/vector；retriever 测 USER/OPERATOR 范围、RRF、无命中和损坏快照。
- P10 单测及 MySQL 8.4 集成验收完成，实测文档撤回后授权查询立即无结果；仍无真实 Embedding/Qdrant、active index 或语义质量证据。检索 route 本身不开放，避免调用方绕过受权 Agent run。

### P11a Harness 检索、引用 metadata 与 Vue 来源清单（2026-09-27）

- 新增 [`SPEC-agent-knowledge-harness.md`](../../SPEC-agent-knowledge-harness.md)。retrieval 默认关闭；启用时要求 Agent MySQL、兼容 Embedding 和 Qdrant 配置齐全，启用前需操作者确认外部请求/费用并构建 active index。Actor 由 Campaign Bearer 实时复核，scope 不由调用者提供。检索 passage 以不可信数据上下文交给 Harness；没有证据时只有成功授权业务 read tool 才能支持动态数据回答，其他回答由固定拒答替代；依赖错误安全失败。
- 在 Agent run 完成前重新检查 active pointer、scope、document/chunk/hash/revocation。过期/撤回证据导致本轮失败，不保存旧版本生成内容。答案与来源引用 metadata 在同一 MySQL `20260927_07` 事务完成；不在引用列复制 passage 文本。Run API 按当前 Actor role 过滤来源元数据；Vue 只显示标题、章节、scope 与短版本指纹，注明资料清单不是答案验证保证。
- 验收：Python 默认 `86 passed / 14 deselected`；MySQL 8.4 Testcontainers `14 passed / 86 deselected`；Ruff、50 个源码文件 mypy 通过。Web 14 files / 39 tests、生产构建通过。测试包含 USER/OPERATOR citation scope、NO_EVIDENCE refusal、只读业务工具 fallback、evidence 在回答完成前失效、metadata 不含原文和 MySQL JSON 原子落库。
- 未验证：没有真实 provider/Qdrant/active index，不代表真实 RAG 可用；检索每次 query 的供应商延迟/成本、召回与回答质量、真实浏览器仍未测。Campaign↔Python↔Vue 隔离进程 smoke 已于 2026-09-27 通过（见 `PROJECT_STATUS.md`）。历史已完成回答不会因后续撤回自动删除；需另行制定历史清除/角色降权政策。

### P12 离线检索评测与 Agent CI（2026-09-27）

- 新增 [`SPEC-agent-knowledge-evaluation.md`](../../SPEC-agent-knowledge-evaluation.md)、版本化 5 case 数据集和 `python -m prizeflux_agent.knowledge_eval`。默认只读取 manifest 白名单语料与本地 JSON，纯 BM25 跑正向文档/章节命中、库外拒答、USER/OPERATOR 隔离；越权案例同时证明受限 OPERATOR 资料若按 OPERATOR 搜索可被命中，防止 ACL 样例本身不相关。支持 Hit@k/MRR、no-evidence false positive、ACL leak/probe 指标和稳定 JSON 报告；报告只含 query ID/结果元数据，不含 passage 原文。
- 报告记录 evaluation dataset SHA、corpus fingerprint、chunker/BM25 revision 与离线 index 指纹，并明确 `model=not_run`、`prompt=not_run`、`embedding=not_used`。当前 top-k=5 的 3 条正向案例均命中目标章节（Hit@5=1.0/MRR=1.0），2 条 no-evidence 案例 0 误命中，ACL 泄漏 0，受限目标探针命中 1。
- `.github/workflows/ci.yml` 增加 Python 3.12 Agent job，锁定依赖后运行 Ruff、mypy、非 MySQL suite 与离线评测；provider adapter 仍用 MockTransport 合同测试，CI 不需要外部密钥。Action 与 uv 均固定版本/commit。
- 本机最终回归：`uv run --locked pytest -m 'not mysql' -q` 99 passed / 14 deselected；Ruff 与 mypy（51 source files）通过。工作流本机未安装 actionlint、未推送仓库，故远程 workflow 结果未知。P12 不建立真实 embedding index、不运行生成模型；真实 Hybrid recall 与答案事实性仍需单独评估。

### P5/P6 实际验证记录（2026-09-27）

- `cd agent-service; uv sync --locked --group dev`：成功；`uv.lock` 固定 LangGraph 1.2.12，Fake 仍为默认 provider。
- `uv run --locked pytest`：43 项通过；覆盖 Fake 与 OpenAI-compatible mock 合同、工具调用循环/预算/拒绝、Java 身份/read/validate HTTP 合同、USER/OPERATOR 工具隔离、用户草案所有权 404 回退与 Decimal 精度。
- `uv run --locked ruff check src tests`：通过；`uv run --locked mypy src`：26 个源码文件通过。
- `cd backend; $env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -q -pl prizeflux-campaign -Dtest=CampaignApiIntegrationTest test`：退出码 0；Docker Engine 29.8.0 / MySQL 8.4 Testcontainers 可用。该 Java 测试验证身份、草案校验、无写入和 owner 隔离。
- `git diff --check`：退出码 0（Windows 提示 LF/CRLF 转换）。没有真实 Campaign 与 Python 两个服务进程联调；没有真实模型调用或费用；Python 当前仅开放健康检查，Agent API 尚未接入持久 RunStore。不能将其描述成完整可面向用户的 Agent。

### P7 实际验证记录（2026-09-27）

- Agent 独立 `agent_session`/`agent_run` MySQL schema 与 Alembic 首迁移；运行账户配置限定为 `mysql+asyncmy`，应用不会启动时建表或自动迁移。已实现 owner-scoped 会话/历史读取、预期版本与 active-run 锁、`clientMessageId + SHA-256(expectedVersion,input)` 幂等、数据库 UTC 租约、递增 fencing token 与 worker ID 校验；对话上下文按完整轮次和字符/轮数上限构造。
- `PFX_AGENT_RUN_MYSQL_TESTS=1 uv run --locked pytest -m mysql -v --tb=short`：临时 MySQL 8.4 Testcontainers 4/4 通过。涵盖首迁移只创建 Agent 表、同键同参重放/异参冲突、两个独立 Windows 进程并发受理同键仅一个 run、旧租约执行者无法心跳/落最终答复、关闭再新建 Store 后 MySQL 历史读取及 owner 隔离；整个测试未配置 Redis，也未连接演示数据库。
- Python 默认回归：47 项通过、4 项 MySQL 专项因 opt-in 被跳过；MySQL 专项测试 4/4 通过；Ruff 与 33 个源码文件的 mypy 检查通过；Alembic head 为 `20260927_01`。`git diff --check` 退出码 0（Git 仅报告 Windows LF→CRLF 提示）。没有 Agent HTTP 业务路由、事件/SSE/取消/中断扫描、保留清理、真实模型请求、Campaign/Python 双服务联调或压测；不对外开放。

### P8 实际验证记录（2026-09-27）

- 新增受保护 FastAPI API：创建会话、本人会话历史、幂等 run 受理、本人 run 状态/取消与 SSE 事件续读。每个请求调用 Campaign 身份端点得到 server-side Actor；Bearer 仅在执行任务期间以内存对象传递，不保存到 Agent schema。Agent RunStore 继续作为会话及结果的权威。
- 新增本地有限并发执行器，执行前复核 Actor；模型生成与只读工具调用期间轮询数据库取消标志，等待中的异步调用被取消；lifespan 周期任务将过期运行终结为 `INTERRUPTED`。SSE 用事件 sequence + `Last-Event-ID` 重放，终态后结束，不触发重新生成。
- `cd agent-service; uv run --locked pytest`：49 passed、8 个 MySQL opt-in tests skipped。`uv run --locked ruff check src tests`、`uv run --locked mypy src`（35 source files）与 `uv run --locked alembic heads`（`20260927_03`）通过。
- `$env:PFX_AGENT_RUN_MYSQL_TESTS='1'; uv run --locked pytest -m mysql -q`：MySQL 8.4 Testcontainers 8 passed。新增真实 DB + ASGI 合同覆盖未鉴权 401、跨 owner 404、run 完成及 SSE 全量/断点续读不重调模型、取消请求中止模型协程和执行并发上限。
- 本记录初次验收后的后续收口：增加 `20260927_04` 和 `DELETE /api/ai/v2/sessions/{id}`。空闲会话立即硬删除并级联 run/event/history；活跃 run 立即隐藏并持久取消，返回 202，完成取消后物理删除。后台默认每小时扫描并按 `updated_at` 保留 30 天；被标记删除的 ACCEPTED run 在不调用模型时取消并清理。
- 当轮后续验证：`uv run --locked pytest -m 'not mysql'` 为 49 passed、12 deselected；MySQL 8.4 Testcontainers 为 12/12 通过（含 owner 删除级联、活动 run 删除/真实取消、30 天清理与 ACCEPTED 回收）；Ruff、35 源码文件 mypy 及 Alembic `20260927_04 (head)` 通过。此后 P14-C 隔离多进程 smoke 与 JWT TTL 到期已通过；浏览器验收、RAG 真正索引/模型、压测和部署仍未完成。

#### P8 运营助手页面接入续验（2026-09-27）

- Python 增加 owner-scoped `POST /api/ai/v2/runs/{id}/resume`，仅本人仍为 `ACCEPTED` 的 run 可触发 dispatch；其他状态稳定返回 409。GET run 补充 owner-scoped 当前输入，供页面在刷新后恢复运行上下文。`/health/ready` 只公开安全 `model_provider` 枚举。
- Vue `OperatorAiView` 已切到独立 Python API client 与 `/agent-api` Vite 代理。页面接通服务端历史、SSE 状态、取消、显式 ACCEPTED 恢复、终态/中断提示及会话删除；sessionStorage 只保存最近 20 个 session/run ID，不保存 prompt、答案或 Bearer。Fake/外部模型边界按健康接口如实显示。
- `agent-service` 默认回归：50 passed / 12 deselected；MySQL 8.4 Testcontainers：12 passed / 50 deselected；Ruff、mypy（35 个源码文件）通过。Docker Engine 29.8.0。
- `web`：Vitest 14 files / 38 tests passed；`npm run build` 的 vue-tsc 与 Vite production build 通过。
- 本段是 M2 当轮历史快照：当时尚未运行 Campaign↔Python↔Vue smoke、Agent DB 账户迁移与长任务 Bearer 失效验证。后续 P14-C 已完成隔离多进程 smoke 和 JWT TTL 到期验收；真实模型/RAG 质量、压力/故障容量与公网部署仍未验证。

### M0 实际验证记录（2026-09-26）

- `backend\\mvnw.cmd -q -pl prizeflux-campaign -am test`：Campaign/Contract 全量模块 198 项通过；随后 `DrawApplicationIntegrationTest` 10 项、`DrawQueueKafkaConsumerIntegrationTest` 2 项单独重跑均通过。
- `npm run test -- --run`：13 个文件、32 项通过；`npm run build` 成功。`git diff --check` 退出码 0，仅报告 Windows LF/CRLF 转换提示。
- 已覆盖队列幂等、租约/fencing、开奖结果优先恢复、活动关闭拒绝、consumer 成功/DLT、Redis 准入/查询限流和页面人工复核。尚未做本机完整服务 smoke、Broker 暂停恢复/rebalance、真实并发/容量测试；测试通过不代表生产容量或生产就绪。

| 场景 | 必须观察到的结果 |
|---|---|
| 库存 1、并发即时/排队开奖 | 最多一次成功扣减；库存不为负，配额/结果/中奖 Outbox 一致 |
| 相同请求并发重放、消息重复 | 不重新随机、不增加业务扣减；返回同一原结果 |
| 开奖提交后进程终止 | 扫描/重投补齐队列结果；无“中奖后又 REJECTED” |
| Kafka 停机、毒消息 | 已受理任务可追踪，积压触发准入保护；恢复可排空；毒消息隔离 |
| 同一会话重复点击/两实例竞争 | CAS 在付费调用前生效；同一次操作返回同一 run |
| 模型超时/客户端掉线 | 有明确状态；不自动重发导致额外费用；取消阻止后续步骤 |
| AI 进程重启 | 会话/报告仍可读，旧运行有 INTERRUPTED 终态；需新授权才重试 |
| Redis 清空或不可用 | Agent 会话历史仍从 MySQL 读取；首版不因 Redis 中缺少聊天 key 而丢失记忆 |
| 提示注入/伪造 owner/role/URL | 无越权工具、无跨用户读取、无任意出站访问 |
| 向量库落后/文档撤回 | 权威元数据过滤阻止失效片段进入模型；引用保持可追溯 |

## 评测与容量目标（目标值，尚无本轮实测结果）

Agent 初始数据集 100 例：30 工具路由/权限，40 检索，20 完整产品任务，10 运行故障/预算。约 25% 按来源文档/问题模板隔离为 holdout；不得用 holdout 调提示词。固定 schema、权限和预算用确定性断言；语义质量使用人工标注/抽查，LLM judge 仅辅助。

检索记录 Recall@5、MRR、无答案题正确拒答率；初始目标 Recall@5 ≥ 85%，需同时报告 BM25-only、dense-only、融合结果与分母。20 个端到端任务中至少 18 个满足预先定义的字段正确性/来源支持要求；这是有限测试集达标，不是泛化准确率声明。安全隔离用例必须全部通过。真实生成另记首个可见事件/最终完成延迟、工具次数、token usage、估算费用及未知项。

Java 压测从 20/50/100/200 requests/s 的阶梯开始，每档预热后持续至少 5 分钟，报告成功受理率、429/503、p50/p95/p99、排队年龄、消费速度、DB 锁等待/连接池、CPU/内存及最终对账。单活动热点与多活动分散分别测；突发负载和长时间积压恢复单独测。千人/万人是参与规模，不能直接等同于同时请求数或 QPS。

初始工程目标：在机器和额定负载明确后，健康系统成功受理 p95 ≤ 500ms，额定负载下受理到结果 p95 ≤ 30s，所有已受理请求最终可查终态；压测范围内零超卖/零重复账本。未达到时保留测量结果并降低公布容量或优化瓶颈，不改口径隐藏错误。供应商延迟不纳入抽奖延迟。

## 验证命令与目录

现有命令在对应目录执行：后端 `backend\\mvnw.cmd -q test`（JAVA_HOME 指向 Java 17，集成测试需 Docker）；Web `npm --prefix web run test -- --run`、`npm --prefix web run build`；静态检查 `git diff --check`。

当前 P4 基础工程的复现命令（在仓库根目录执行）：

```powershell
Set-Location -LiteralPath '.\agent-service'
uv sync --locked --group dev
uv run --locked pytest
uv run --locked ruff check src tests
uv run --locked mypy src
uv run --locked python -m prizeflux_agent
```

P4 实测锁定环境为 FastAPI 0.141.1 / Pydantic 2.13.5 / pydantic-settings 2.15.0 / Uvicorn 0.54.0；精确传递依赖见 `agent-service/uv.lock`。本机 smoke 对两个健康端点均得到 HTTP 200；ready 此阶段只声明配置就绪、不声称 Campaign/模型可用。P9 build 与 P12 offline evaluation 命令已写入 `agent-service/README.md`；真实 index/provider 操作仍需外部配置与用户授权。

评测与压测运行产物落忽略的 `artifacts/`；可公开的脱敏报告与小型原始样本进入 `docs/evidence/`，记录代码 commit/工作树说明。真实密钥/会话内容不入 Git。阶段内只做必要编译与定向验证，M0/M2/M4/M5 集中回归。

## 面试竞争力与交付包

Java 重点：事务不变量、幂等作用域、Outbox 至少一次、队列恢复窗口、Redis 准入、Kafka 分区与数据库热点。Python 重点：有业务价值的工具循环、运行幂等/预算、会话隔离、RAG 索引与权限、评测与失败恢复。每个亮点要能定位源码、演示故障、展示原始证据并解释取舍。

发布候选应提供：5 分钟产品演示脚本；两张架构/时序图；一份构建启动指南；一份并发/故障报告；一份 Agent/RAG 评测报告；真实模型与 Fake 的能力说明；迁移回滚说明。产品故事保持统一，简历按岗位突出 Java 或 Agent 主线，不将同一业务重复包装成两个完全独立经历，也不把使用开源运行时写成底层自研。

本轮不承诺日期。若需压缩范围，优先延后运营异常诊断、多模态导入和重排模型，保留权限、业务幂等、运行状态、引用与必要评测。
