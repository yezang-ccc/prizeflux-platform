# P14 本地运行与交付复核

日期：2026-09-27。归属：既定 [P14](docs/architecture/product-delivery-plan.md)，不是新增产品主线。P14-A/B/C/D 已实施并在本机验收；P14-E1/E2 的配置与代码已落入 Agent allowlist、Java/Python/Web 镜像、隔离 Compose、schema 账户初始化、本地运营 bootstrap 和 Nginx 代理；静态验证结果由 `PROJECT_STATUS.md` 顶部记录。Docker Hub 鉴权再次超时，镜像 build、容器运行、Nginx 实测及 E2E 均未完成；P14-F 的 AI 故障隔离、三 schema 逻辑备份恢复、双消费者 Kafka group rebalance 已通过隔离验收，产品卷备份脚本亦已在随机临时双卷上导出并恢复成功。当前机器尚无真正的产品卷，故实际产品数据备份/恢复、加密/留存和 P15 仍待完成。本规格不代表公开部署完成。

## 结论与目标

保留 Java 业务权威 + Kafka 异步通知 + Python 只读 Agent + Vue 的边界，不重写已有领域代码。不引入 Kubernetes、微服务注册中心或 Redis 聊天记忆。先让隔离环境中的完整产品可复现，再补故障与容量证据。

默认本机、Fake 模型、知识检索关闭；不调用收费服务、不部署公网、不接管原有 3306 MySQL。真实模型和知识索引仍归 P13/相关 RAG 验收，不能以 Fake 通过替代。

## 源码复核发现

| 优先级 | 证据 | 影响与处置 |
|---|---|---|
| 启动配置映射 | 私有配置使用旧 `MYSQL_PASSWORD/MYSQL_ROOT_PASSWORD` 名称；Java local-full 仍使用显式 `PFX_DB_URL`，不会从 `PFX_DB_PORT` 自动构造 JDBC URL | 预检兼容两个旧密码键，不回显、不改写私有 env；隔离 smoke 显式传入 JDBC URL。持久本机启动仍按 local-full 合同单独配置 |
| 阻塞完整产品 | Campaign、Worker、Agent 各自 schema/运行账号和迁移入口必须保持隔离 | P14-C 已在临时实例创建三个 schema/独立 DDL 与 runtime 账号并验证权限；持久 local-full 的用户配置仍需按合同落实 |
| 阻塞容器联调 | `agent-service/.../config.py` 拒绝所有非 loopback HTTP Campaign URL | `http://campaign:8081` 不可直接配置；本机首轮走 loopback，容器阶段增加严格限定的内部信任策略 |
| 就绪假阳性 | `agent-service/.../api/health.py` 无依赖探测即返回 READY，required_dependencies 为空 | 增加真实 DB/schema 就绪检查；存活与可接请求分离，不能以当前 200 作为成功门槛 |
| 授权口径不完整 | `AuthController.me()` 读取 Actor；`ActorJwtAuthenticationConverter` 只解析已验证 JWT claims，没有查询账号当前状态 | 周期复核可以重新检验令牌有效期和签名，但不能保证数据库角色变化/封禁立即生效。现有身份变化单测只证明客户端对变化结果的处理 |
| 准入不完整（P14-D 已收口） | 新请求先查询 owner-scoped 幂等键，再由 `RunExecutor` 预留本进程容量，之后才写 MySQL；Semaphore 外的 task 数受总 outstanding 上限约束 | 默认 4 执行 + 16 等待、每 owner 最多 4 个 outstanding；过载稳定返回 429，不写 ACCEPTED。计数仅本进程有效，多实例总量仍未实现/验收 |
| 迁移未结束 | Vue 运营助手走 `/agent-api`；Creator 草案检查仍依赖 Java AI | 保留 8082 legacy 服务及其路由；不能现在删掉 Java AI 或宣称 Python 已完全接管 |

以上是配置/源码核对，不是渗透测试或全仓安全认证。原 3306 的 schema、凭据与业务用途未查询；不得根据端口存在推断它属于本项目。

## 运行合同

技术栈沿用仓库锁定版本：Java 17 / Spring Boot 3.5.x / MySQL 8.4 / Redis / Kafka；Python + uv.lock / FastAPI / SQLAlchemy / Alembic / LangGraph；Vue 3 + Vite。镜像在容器化实施时固定版本并记录 digest，禁止发布候选使用 latest。

### 第一阶段：隔离进程 smoke

用临时且明确标记的测试基础设施、动态可用端口、合成账号/数据运行 Campaign、Python 和 Web；需要 Creator 验收时运行 legacy AI。不复用原 3306、不重置现有容器或私有 env。端口通过同一启动器显式传递，避免 JDBC 默认回落到 3306。测试资源登记在临时清单中；清理只操作该清单，不执行广域 prune。

### 第二阶段：持久本机 local-full

| 单元 | 权威数据/职责 | 运行约束 |
|---|---|---|
| Campaign | prizeflux；HTTP、业务事务、Outbox relay | 唯一业务写入口；显式 JDBC URL |
| draw-executor | 同一业务 schema；复用开奖事务 | 独立进程，无 HTTP 对外端口；禁用 relay；不独立执行迁移 |
| fulfillment-worker | prizeflux_worker；发奖账本 | 独立进程、独立账户，演示发奖仍明确 simulated |
| Python Agent | prizeflux_agent；会话/run/event/知识 metadata | 不持有 JWT 签名密钥，不具备前两库权限 |
| legacy Java AI | Creator 现有合同/回退 | 保留直到 Python 对等功能验收；不读取 Python 表 |
| Web | 静态页面与 API 代理 | 开发先沿用 Vite；发布构建的同源代理保留三条 API 路由 |

同机 MySQL 可共用实例，但 Campaign、Worker、Agent 使用不同 schema 和独立账号；迁移账户拥有各自 schema DDL，运行账户仅获实际需要的 DML/查询权限。draw-executor 只获业务 schema 所需权限；运行账户不得跨库访问。验证使用真实拒绝访问测试，而非仅检查用户名不同。

迁移必须先于相应业务入口：基础设施先健康；Campaign 与 Worker 分别由各自服务在启动阶段使用专属 DDL 账号运行 Flyway，再开放 HTTP/开始消费；Agent 使用独立的一次性 Alembic job，job 成功后才启动 Agent API；draw-executor 等 Campaign 健康后启动，并明确关闭 Flyway。Campaign 与 Worker 可以并行迁移不同 schema，权限隔离且互不依赖。迁移失败时相应服务不得就绪。迁移成功也不代表业务端到端通过。

所有本机映射绑定 127.0.0.1，数据库/Redis/Kafka 无公网映射；内部地址与宿主机地址分别配置。持久栈建议 MySQL 3307，但启动器必须先检查占用，不能杀占用进程。Kafka advertised listeners 与消费者实际网络一致；不得把宿主机 19092 和容器端口混用。

### 网络、健康与授权

- 本机 loopback 维持现有 HTTP 支持。容器仅在显式 local 环境配置精确的内部 Campaign origin（scheme/host/port）例外；只允许指定服务，不放开全部私网或所有 HTTP。production 仍要求 HTTPS。出站禁止将 Bearer 随重定向转发；例外不传播到远程模型/Embedding。
- `/health/live` 只证明进程活着；`/health/ready` 对 Agent DB 连通和预期 migration head 做有超时的探测，失败 503，响应不含 DSN/凭据/堆栈。Campaign 是否可用另以限时无凭据探测和端到端认证 smoke 判定；不能伪造身份探测。供应商健康检查不触发生成/计费。
- Python 不可用不能使 Java 抽奖不可用；反向依赖禁止。RAG 关闭不要求 Qdrant/Embedding；开启后需实际 active index，不能只判断配置齐全。
- 当前 JWT 仍是短期 claims 模型，撤销/角色变更实时生效未实现。第一轮验证真实过期（含 decoder 时钟容差）及 Agent 完成前拒绝落答案；即时撤销如需实现，应另写账号状态/token-version 合同，并同时用于所有 Java 入口，不能只修 `/me` 制造不一致。
- Agent 准入区分本进程执行槽、等待上限、单用户上限与多实例总上限。P14-D 已实现默认单进程 4 执行 + 16 等待、每 owner 4 个 outstanding；新任务先预留容量再持久接受，超额稳定返回 429/`agent_capacity_exceeded` + `Retry-After: 2`，不留下新 ACCEPTED。owner-scoped 同键重放先查 MySQL，不创建额外 reservation；旧 ACCEPTED 记录仅在容量可用时重 dispatch，否则仍可显式 resume。完成、异常、取消和关闭均回收进程内 reservation；进程内存不作为跨进程事实。多实例全局限制在声称横向扩容前仍须用数据库权威计数/租约验收，不能把各实例 semaphore 相加冒充全局预算。
- SSE 代理禁用缓冲，支持认证头与断点续读；断流不自动创建新 run。legacy/Python 路由切换不得把历史记录互相导入。

## 实施顺序与文件边界

1. P14-A 配置预检：`deploy/check-local-prerequisites.ps1` 和 foundation 模板只报告缺失键/端口冲突；Compose 支持用户已授权的两个旧密码键作为后备，但不写目标配置、不启动容器。
2. P14-B 就绪合同：Agent health 对 DB 做有 2 秒上限的只读探测，并将已安装 `alembic_version` 与仓库迁移图 head 比较；DB 不可用、schema 落后或未配置 DB 均不能 READY。保留原 provider 标识供 UI 展示。
3. P14-C 隔离 smoke（已完成）：执行 `deploy/run-isolated-process-smoke.ps1`，在唯一无卷 Compose 项目中验证 Java/Python/Vue/legacy AI、独立 DDL/runtime 账户和跨 schema/DDL 拒绝，以及用户身份、JWT 到期、Agent owner/session/run/SSE、Creator Fake 只读校验。它不替代队列开奖/Outbox→Kafka 的独立验证，也不操作既有数据或服务。先完成本项再扩容器包装。
4. P14-D 准入收口（代码/定向 MySQL 验收完成）：`PFX_AGENT_MAX_CONCURRENT_RUNS`、`PFX_AGENT_MAX_WAITING_RUNS`、`PFX_AGENT_MAX_RUNS_PER_OWNER`；owner-scoped 幂等预查 → 本进程 reservation → MySQL 接受 → task dispatch；异常释放；稳定 429/Retry-After；多实例总额度明确未实现。未引入 Redis 记忆。
5. P14-E 完整容器与网络策略：应用镜像、profiles、独立迁移账户、内部 HTTP 精确例外和反向代理。E1 保留的 Agent local-only Campaign origin 例外只匹配配置的 scheme/host/port，不影响模型/Embedding/Qdrant，Campaign client 不跟随重定向。E2 已新增 Java 多模块镜像、Agent 镜像、本地 Vue+Nginx 多阶段镜像、`local-full` Compose、三个 schema/隔离 DDL/runtime 账号、独立 Agent Alembic job、draw-executor 独立进程、loopback-only Web 入口与 SSE 无缓冲代理。Campaign 以 Spring/Flyway 启动，Java 服务健康条件供 bootstrap/执行器/代理依赖；Agent 等待迁移完成且 readiness 成功后代理才启动。产品卷明确命名为 `prizeflux_product_mysql_data`，与旧 foundation 卷隔离。随机秘密生成脚本只新建 ignored `deploy/.env.product`，不输出值；一次性运营 bootstrap 交互收取 SecureString，不写入文件。此状态仍只是配置/代码：先前 Docker Hub token 超时，镜像 build、Nginx 运行态 `nginx -t`、Compose 启动/E2E smoke 与 image digest 锁定均未验证。
6. P14-F 恢复与交付：AI 隔离已通过真实本地进程故障演练：停止 Python Agent 和 legacy Java AI，确认端口关闭后即时抽奖成功；排队恢复扫描延迟 10 分钟，确认 DrawQueued Outbox 经 Kafka 发布并完成中奖。三套真实迁移 schema 的停写 `mysqldump --single-transaction` 已恢复到带本轮标签的新 named volume，并核对 Campaign 结果/库存、Worker 成功账本、Agent 已完成 run；只在身份核验后清理本轮临时恢复卷。另以两份 Campaign JVM 验证同一 Kafka consumer group 从 generation 2 重平衡至 generation 3、12 个分区 6/6 分配，并在 DB 周期扫描延迟时完成同活动的 24 个排队请求，证据见 `docs/baseline/draw-queue-kafka-rebalance-2026-09-27.json`。新增产品卷逻辑备份脚本会核对容器/卷/schema/InnoDB，并导出未加密 SQL；已在随机标记的隔离 MySQL 源卷上导出、恢复到另一个新卷并核对样例行。操作见 `deploy/container-local.md`，复测命令为 `scripts/smoke-backup-product-mysql.ps1`。当前仍没有实际产品卷，此实验不代表真实用户数据、异地备份、加密保留或 PITR 已验收；产品卷不得随普通退出删除。随后做 P15 分阶容量验证。

每批修改相关约 3～5 个文件，按源码现有分层与命名，避免无关重构。例：配置使用显式字段并维持默认拒绝：

```python
max_concurrent_runs: int = Field(default=4, ge=1, le=32)
# 执行并发字段不承担等待队列或全局配额语义；新增字段分别校验。
```

## 验证与成功标准

- 两个不同用户访问 session/run/history/SSE，跨 owner 被拒绝；真实 JWT 过期后 run 无答案提交；日志与 DB 不含 Bearer。
- 新环境能登录、创建/审核活动、即时抽奖、排队到结果、查询历史；Python Fake 能完成受权只读工具任务并标识 Fake，legacy Creator 不回归。
- Agent 重启后历史保留；旧活跃 run 最终 INTERRUPTED，不自动重新调用模型。SSE 重连不新增 run。
- 断开 Agent DB readiness=503；恢复后正常。AI 停止后 Java 即时/排队业务仍可运行。
- 备份恢复到新卷后分别核验业务/Worker/Agent 数据；跨 schema 权限拒绝；旧数据库零操作。
- 假模型不计入真实模型质量；本轮不公布 QPS 或万人并发结论。P15 必须带环境、原始指标及业务对账。

现有可执行命令（在各自目录；PowerShell）：

```powershell
# 仓库根目录：只做验证，不输出完整解析后的配置以免泄密
docker compose --env-file deploy/.env -f deploy/compose.foundation.yml config --quiet
git diff --check
# agent-service 目录：仅改动后定向检查；里程碑再全量
uv run --locked pytest -m 'not mysql' -q
uv run --locked ruff check src tests
uv run --locked mypy src
# 仓库根目录：UI 联调前构建
npm --prefix web run build
```

本机隔离 smoke 可重复执行：`$env:JAVA_HOME='D:\java\jdk-17'; .\deploy\run-isolated-process-smoke.ps1`。脚本动态分配 loopback 端口、创建随机临时账号与无卷 Compose 项目，完成后只清理自己的进程/项目。后端定向构建入口仍为 `backend\mvnw.cmd`；阶段末再集中回归。

## 边界与未决项

始终保留用户未提交内容、记录实际结果、固定依赖、脱敏证据。禁止打印私有 env、自动覆盖凭据、对旧库执行迁移、删除持久卷或把假模型称为真实 Agent 质量通过。

真实供应商/Embedding 与费用、远程写入、公开部署、已有持久数据迁移仍需用户授权。本地隔离 smoke 不需要这些选择。账号即时撤销及真实发布域名/TLS 不是本轮已完成能力。

复核结论：架构方向通过，发布门禁未通过。P14-C 隔离多进程 smoke、P14-D Agent 本进程有界准入与 P14-F Kafka 恢复/consumer group rebalance/AI 故障隔离/逻辑备份恢复均有各自的隔离证据；备份 helper 的隔离源卷导出及新卷恢复也实测通过。P14-E1 配置策略测试及 E2 Compose/脚本静态检查不能代替镜像构建和容器端到端验收。Docker Hub OAuth 当前持续超时；网络恢复后先构建并启动隔离产品 Compose，核对迁移/权限/代理/SSE/抽奖履约，再在新产品卷验证备份和恢复，最后完成 P15 容量证据。
