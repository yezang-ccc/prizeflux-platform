# PrizeFlux 聚焦版能力地图

2026-10-02 当前 K2/K3：Clash 局域网接入后正式 Agent/迁移镜像构建、迁移和健康检查成功；修复在线 BM25 路径。原真实索引 14 chunks 由正式镜像核验，开启检索的单次 Fake 应用以真实 MySQL/Qdrant 验证 USER/OPERATOR scope；独立测试版本的真实故障注入保持旧 active 指针。K2 容器关口收口；K3 仍缺带鉴权 HTTP Run/浏览器与来源显示，正式服务检索默认关闭。当前 Agent 非 MySQL 163 项、隔离 MySQL 17 项通过；详见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 顶部。下方旧日期段落是过程记录。

J4 精确验收边界补充（2026-09-30）：Worker 重复 Kafka 记录后显式将 group offset 从2回退到0并重放，MySQL 账本/marker仍唯一，集成测试通过；“DB提交后、offset提交前崩溃”及双消费者 rebalance 未验证，J4保持部分完成。后续状态见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

2026-09-30 最新：有界Relay并发8/consumer12/100ms候选在本机50 RPS×300秒R1/R2/R3均通过脚本门槛：终态P95=3.878/2.941/0.813秒，保护拒绝2/6/1、dropped=0，账本全部对账。R2/R3日志原因汇总为连接池压力，但覆盖准备/预热/测量阶段，不能与HTTP逐请求关联。候选Kafka停机恢复首测发现100ms轮询耗尽3次重试并死信；已新增MySQL `available_at` 指数退避（2秒起、封顶30秒），定向22项和更新后候选Kafka恢复通过。仍是单机单Broker实验，不外推生产/万人容量；生产默认并发1/间隔5秒未改。J4其余重复投递/提交窗口检查、J1运行态、K/Q/R尚未完成，见[当前状态](PROJECT_STATUS.md)。

2026-09-30 最新长测：100ms/consumer8的50 RPS×300秒R1性能FAIL（终态P95=29.884秒），10ms对照更差（72.756秒）；均全部受理、最终业务对账正确。短测通过不代表稳定容量，停止重复失败档/盲调参数。新增Relay数据库分段遥测及采样，通过定向检查，完整运行接线待烟测；按最新状态推进J3，K/Q/R未完成。

2026-09-30 最新J3：保持生产默认值，隔离benchmark两步单变量探索得到Relay100ms/consumer8候选，50 RPS×60秒单轮PASS、终态P95=4.439秒、3001全受理且业务对账正确；不是50 RPS×5分钟稳定容量。下一步三轮稳定复测及候选故障回归，优化过程已写项目深挖手册。下方同日诊断失败记录保留作对照。

2026-09-30 最新：Docker可用，新诊断完整Spring接线隔离烟测通过；50 RPS同条件复现仍失败（终态P95=50.672秒，最终业务对账通过）。新采样支持优先调查Relay批间等待与串行总成本，尚未证明优化收益，不升档。可维护性规范及技术债已记录，采样职责从主脚本拆出并通过离线合同检查；不是全仓质量认证。下一步按证据推进J3，见[当前状态](PROJECT_STATUS.md)与[可维护性约束](docs/architecture/maintainability.md)。

2026-10-01 当前增量：J3 后续候选三轮及Kafka停机恢复证据、J4重复重放证据见 `PROJECT_STATUS.md`，精确进程崩溃窗口仍未完成。K1语料已更新；K2 BGE中文小模型固定权重/哈希的本机CPU适配和真实向量烟测完成，容器构建因Docker侧PyPI TLS EOF未验收，真实Qdrant active index与召回评测仍属K3/Q。以下旧日期段落为历史过程，不代表当前最终状态；以状态文档顶部和执行清单为准。

2026-09-29 历史进度：J2 开放模型压测入口和隔离对账已实现；25 RPS×300秒、Outbox间隔1秒/队列消费者并发4的三轮本机候选测试均通过，范围限单机单用户单活动，不代表生产容量。50 RPS×60秒多轮诊断均未达性能门槛：扩大池/消费者/活动数没有把约51秒的终态P95降至目标；最后一轮测得约26次/秒处理速率、约1447条峰值积压，最终业务账本对账正确。现有采样没有支持“连接池等待/慢数据库段是主因”的证据；停止升档，下一步聚焦 Outbox/Kafka 消费确认与应用等待。J1 可选 Prometheus/Grafana 运行态因 Docker Desktop 宿主代理不通仍未验收。项目难点和面试亮点及证据边界见[深挖手册](docs/interview/prizeflux-project-deep-dive.md)。详见 [当前状态](PROJECT_STATUS.md)、[执行清单](tasks/competitiveness-enhancement-tasks.md) 与[基准说明](docs/baseline/README.md)。

2026-09-28 后续架构已形成：[双项目竞争力增强](docs/architecture/competitiveness-enhancement-plan.md)。沿用本图业务范围，细化 P13/P14/P15、Python Creator 工作流、真实 RAG 与质量证据；[增强清单](tasks/competitiveness-enhancement-tasks.md) 为后续入口。仅方案完成，未改变以下功能完成状态。历史分阶段队列实验不代表正常稳态最大容量。

2026-09-28 交付复核与实施：P14-A/B/C/D 已验收；P14-C 覆盖三 schema 独立迁移/运行账户、Campaign/Worker/Python Agent/legacy AI/Vue 多进程、认证/JWT、Agent owner 隔离与 SSE。P14-D 的有界准入仅为单进程配额，不提供多实例全局限额。P14-E 产品镜像已成功构建，隔离 `local-full` Compose 九个长期服务均在运行；MySQL、Redis、Kafka、Campaign、legacy AI、Python Agent、Nginx 的 Compose 健康检查通过，Worker 与 Draw Executor 未配置 healthcheck。网页 `/login` 与代理健康路由返回 200，Agent 经 Nginx 返回 READY。浏览器注册/登录、USER/OPERATOR、即时/排队抽奖和 Agent 交互仍待人工端到端验收；immutable digest 未锁定。P14-F 的 Kafka 故障恢复、consumer group rebalance、AI 停机隔离与随机卷备份恢复均有本地证据；真实产品数据恢复策略未验收。P15 即时/排队抽奖有 100/20、250/50、500/100 基准及多用户/多活动补充单轮；仅为单机样本，不能外推生产容量。完整命令、测试、限制与未提交状态见 [交接状态](PROJECT_STATUS.md)；不可公网发布，也不能声称账号即时撤销或生产容量。

P15 原三轮压测后的保护来源审计：500/100 单独一轮中 73 个 503 均由 MySQL pool 水位触发，427 个受理任务全部闭环；backlog=5 审计轮实际受理 9 个（启动时 backlog=9），91 个拒绝均由 backlog 阈值触发并全部排空。另有 20 个合成用户/单活动 500/100 单轮（500/500受理）及 10 用户/5 活动 100/20 单轮（99/100受理，1 次 MySQL pool 保护拒绝），两轮已受理项均完成结果/Outbox/Worker对账。多活动只覆盖单轮、单消费者本机混合，未覆盖多 Campaign 实例或生产容量。现有 MySQL 压力快照是 best-effort shedding 而非严格并发硬上限；内部来源日志与原始 JSON 见 [基准报告](docs/baseline/README.md)。

## 2026-09-26 最新架构方向

用户已要求将 Agent 作为独立 Python 子项目，并授权制定完整产品架构和实施方案。目标为 Java 活动/高峰抽奖平台 + Python 活动 Copilot + 共用 Vue 3 界面；当前 Java Harness 保留为迁移基线。新的 Agent 模块及顺序见 [Python 能力地图](CAPABILITY-MAP-AGENT-PYTHON.md)，统一技术决策见 [产品架构](docs/architecture/product-agent-blueprint.md)，逐项验收见 [交付计划](docs/architecture/product-delivery-plan.md)。截至 2026-09-27，P4～P8 已自动化验证；P9/P10 构建与受权 retrieval 内核通过 mock/MySQL；P11a 已接 Harness/Run API/Vue 来源清单；P12 离线 BM25 评测与 Agent CI 已通过本机验收；P14-C 多进程联调及 JWT 过期验收已通过。检索默认关闭、真实 active index 尚未构建，真实检索/模型质量评测与发布验收仍未完成。下方 Java Harness 状态与编号仅说明已有实现，不再指导新增 Java 模型适配工作。

状态：2026-09-24 经用户确认，以 Java 17 开发 Agent Harness。2026-09-25 用户批准完整 Vue 3 演示前端，并确认产品升级为“普通 USER 可创建自己的活动草稿、运营员审核后发布”；同一账号保留参与者能力，不新增 `CREATOR` 全局角色。2026-09-26 用户批准新增独立异步排队开奖、保留即时抽奖，并批准可切换模型适配器（Fake 默认）。本次队列是随机开奖任务，不伪装成先到先得的秒杀成功承诺。新增模块的边界和顺序见 [`CAPABILITY-MAP-CREATOR-PLATFORM.md`](CAPABILITY-MAP-CREATOR-PLATFORM.md)、[`CAPABILITY-MAP-WEB.md`](CAPABILITY-MAP-WEB.md) 与 [`SPEC-high-peak-queue.md`](SPEC-high-peak-queue.md)。用户已于 2026-09-27 退休旧版 64 项清单；当前范围与交付顺序只按聚焦地图和产品交付计划维护。主线保留高并发、分布式、消息队列与 Java Agent Harness。

## 模块与依赖

| ID | 可独立验收的职责 | 依赖 |
|---|---|---|
| `campaign-core` | 保留现有 Java 活动、认证、抽奖、MySQL 库存/次数/幂等/结果链路；补齐真实 HTTP 闭环 | 现有后端 |
| `traffic-proof` | 聚焦限流与并发验证、故障实验和原始指标；Redis 只用于抗滥用/性能辅助，不充当库存事实 | `campaign-core` |
| `event-fulfillment` | Outbox → Kafka → Worker 持久化幂等发奖、失败重试/人工处理、独立进程联调 | `campaign-core` |
| `harness-runtime` | 自建最小 Agent 循环：模型适配、消息状态、受控工具调用、停止条件、超时和预算；Fake Model 离线可测 | 无业务依赖 |
| `harness-safety` | 类型化只读工具注册、服务端授权、参数验证、调用轨迹；通过受授权的 Campaign 查询 HTTP 接入业务 | `harness-runtime`、`campaign-core` |
| `harness-evidence` | 上下文压缩/会话、确定性回放与评测、可操作 CLI 演示；真实模型是后续可选适配 | `harness-runtime`、`harness-safety` |
| `campaign-read-api` | 为用户端与运营台提供按角色授权的活动列表、奖品详情和草稿配置读取 | `campaign-core`、身份认证 |
| `vue3-product-web` | Vue 3 用户与运营前端：登录、活动浏览/抽奖/历史、活动配置/发布 | `campaign-read-api`、Campaign 写接口 |
| `vue3-agent-console` | 运营只读 Agent 会话界面，明确展示 Fake Model 与进程内会话边界 | `vue3-product-web`、`harness-safety`、AI HTTP API |
| `campaign-ownership` | USER 创建活动草稿、服务端归属、owner-scoped 编辑/配置/提交 | Campaign、身份认证 |
| `campaign-review` | 运营审核、驳回理由、通过即冻结并发布、追加审核记录 | `campaign-ownership`、配置校验 |
| `creator-console` | 我的活动与运营待审核工作区 | `campaign-ownership`、`campaign-review` |
| `creator-agent-copilot` | 以 Harness 确定性工具校验草案并给建议，需用户显式保存 | `campaign-ownership`、Harness |
| `queued-draw` | 即时抽奖之外的异步高峰开奖；MySQL 保存任务与 Outbox，独立 Kafka topic 消峰，按用户归属查询状态 | `campaign-core`、`event-fulfillment` |
| `agent-model-adapters` | ModelPort 可配置 Fake（默认）或 OpenAI-compatible 供应商适配；超时/输出受限，模型不能扩大只读工具权限 | `harness-runtime`、`harness-safety` |
| `vue3-experience-refresh` | 优化用户抽奖/排队状态和运营工作台的信息层级、可访问性与响应式表现 | `vue3-product-web`、`queued-draw` |

`harness-runtime`、`harness-safety`、`harness-evidence` 与 `agent-model-adapters` 表示已有 Java Harness 基线及其历史编号；Java model adapter 当前有未接线原型，新增 Agent 主线改由 `CAPABILITY-MAP-AGENT-PYTHON.md` 和 `docs/architecture/product-delivery-plan.md` 约束，不再双线扩展 Java 与 Python 两套 Harness。

## 构建顺序与取舍

当前建设顺序以架构交付计划为准：M0 队列自动化、单消费者 Kafka 故障恢复及双消费者 rebalance 已有隔离证据；12 分区、单活动 24 请求验证了分区与业务对账，不等于容量压测。Python P4～P8 Harness/API/UI 已验收，P9/P10 retrieval 有 mock/MySQL 证据，P11a 已把受权 evidence/无证据拒答/引用 metadata 接入 Python run 和 Vue 来源清单，P12 离线 BM25 评测集与 Agent CI 已完成本机验证。P14-C 真实多进程 smoke 和 JWT 过期拒绝现已通过；P14-F 的 AI 停机隔离、逻辑备份恢复与临时产品卷备份工具验证完成。P14-E1/E2 已有容器/Compose/代理配置，但镜像构建、容器启动、Nginx 运行检查及 digest 尚未验收（最近一次 Docker Hub OAuth 拉取超时）。P15 即时抽奖和异步队列 100/20、250/50 两档各有三轮数据；队列 500/100 三轮中两轮各出现 1 个通用 503/PFX-9001，但三轮已受理项均完整消费；backlog=5 保护场景也有一轮实证。检索默认关闭且无真实 active index，真实 Embedding/Qdrant 与模型评测仍需用户配置和授权。既有 `vue3-product-web` 和 Java AI 保留用于 Creator 草案校验/迁移回退。每批做定向检查、阶段末集中回归；简历展示必须提供真实抽奖链路与可复现 Agent 证据，不能用架构图代替运行结果。

新增创作者产品切片 `campaign-ownership` → `campaign-review` → `creator-console` → `creator-agent-copilot` 首版已实现；接口与验收证据见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。普通 USER 可自助注册并创建自己的草稿；OPERATOR 仍受控初始化且负责跨用户审核。Agent 只建议/校验，当前使用 Fake Model。注册仍缺邮箱/验证码/滥用监控，不等于可公网部署。旧版 64 项任务已退休，不再作为交付清单。

高峰队列代码切片已完成并通过所列自动化用例；发奖 Outbox→Worker 与排队 DrawQueued Outbox→consumer 均有 Kafka 单节点停机恢复证据；双消费者 rebalance 已验证 12 分区 6/6、同活动 24 个队列请求无扫描兜底完成。产品备份命令通过随机源卷→全新恢复卷的隔离验证，但实际产品卷尚未创建，明文备份的加密/排程/异地留存与 PITR 未实现；完整 local-full smoke 仍未完成。P15 即时抽奖 100/20、250/50 两档各三轮，异步队列 100/20、250/50、500/100 三档各三轮有本机证据；高档有少量通用 503/PFX-9001，已受理任务均完整消费。另以 backlog=5 验证容量保护及已受理子集完整消费。Python P4～P8 已实现 Fake/HTTP provider、Harness、Campaign 身份委托/只读工具、Agent 自有 MySQL 会话/运行持久化、HTTP/SSE/取消恢复、删除和 30 天清理；P11a 为 Harness 接入受权知识检索、确定性无证据拒答、引用 metadata API 和 Vue 来源清单。P9/P10/P11a 通过 mock 与隔离 MySQL 测试，但真实检索默认关闭且无 active index，不能宣称真实 RAG 已上线。Kafka 是消息传输与缓冲，不是业务事实；MySQL 继续负责排队记录、身份、幂等、次数、库存和开奖结果。Agent 会话同样以独立 MySQL schema 为事实来源，不依赖 Redis。真实供应商/Embedding 调用须等用户配置密钥并确认费用，远程 Qdrant 写入须单独确认。队列开奖仍按现有随机概率合同，不等于传统先到先得的库存秒杀；若要新增该语义，另立决策和任务。

## 参考与不可逾越的边界

- `SWE-agent/mini-swe-agent`：学习最小模型→动作→结果循环；不照搬 Bash 执行工具。
- `HKUDS/OpenHarness`：参考工具、权限、上下文分层；不照搬大而全的实现。
- `langchain-ai/deepagents`：参考状态与扩展设计；不以框架封装隐藏首版控制循环。
- 现有 Harness 位于 Java 17 `backend/prizeflux-ai`，作为迁移基线保留。新主线按上述方案在 `agent-service/` 建独立 Python 服务，采用 LangGraph 控制流与项目自己的 Harness 策略；Campaign/Worker 保持现有权威业务与事务架构。
- AI 只能通过受授权、窄范围的工具辅助草案校验，不参与随机开奖、库存扣减、活动审核或发布。凭据、真实模型费用和公开部署都需另行决定；首版只用 Fake Model，不要求密钥。
- 参考项目用于学习设计，不复制源码；实际采用第三方依赖前核对许可证、维护状态和安全边界。
