# PrizeFlux 本机流量与依赖故障证据

记录日期：2026-09-24。这里的结果用于简历和面试时展示“本机可重复验证”，不是生产容量、云端 SLA 或 Redis 提速结论。

## 抽奖 HTTP 并发基准

通过 `scripts/smoke-draw-worker-cross-process.ps1` 启动隔离的 Campaign/Worker MySQL、Redis（对照组启用时）、Kafka，以及独立 Campaign 和 Worker JVM。每轮使用同一参数：40 个认证抽奖请求、10 路客户端并发、单一用户、20 个确定概率为 1 的奖品库存。运营账号、用户、JWT、数据库和活动均为本轮随机生成的本机临时数据。

限流开启和关闭的两组执行相同预热：健康查询、认证活动查询、一个缺少 `requestKey` 的抽奖 HTTP 请求（在 Controller 校验处被拒绝，不写业务状态）。Redis 限流阈值设为 45 次，预热与正式 40 次都不会触发 429。计时仅包括并发抽奖 HTTP 批次，不包括环境启动、登录、发奖等待或容器清理。每份 JSON 保存逐请求状态、业务结果和延迟，以及 MySQL 库存、Outbox 和 Worker 正确性核对；不保存令牌、密码或请求幂等键。

环境：Windows 11（build 26200）、12 个逻辑处理器、JDK 17.0.12、PowerShell 7.6.5、Docker Engine 29.8.0；MySQL 使用 `mysql:8.4`、Kafka 使用单节点 `apache/kafka:3.9.1`，Redis 组使用 `redis:7.4-alpine`。Campaign 配置 Redis connect/command timeout 均为 1 秒，JDBC pool connection timeout 为 3000 毫秒。

| 配置 | 重复次数 | 吞吐中位数 / 范围（请求/秒） | p50 中位数 / 范围（毫秒） | p95 中位数 / 范围（毫秒） | p99 中位数 / 范围（毫秒） |
|---|---:|---:|---:|---:|---:|
| Redis 限流关闭 | 3 | 38.77 / 33.27–39.75 | 204.44 / 114.58–219.40 | 229.56 / 157.80–248.25 | 237.37 / 170.12–250.69 |
| Redis 限流开启 | 3 | 47.57 / 44.29–47.65 | 144.95 / 129.62–160.51 | 176.38 / 141.19–200.79 | 182.91 / 155.78–201.85 |

正式三组配对共 6 轮均为 HTTP 200、40 条抽奖结果、20 次中奖和 20 次库存耗尽；库存最终为 0，Campaign Outbox 有 20 条 `SENT`，Worker 账本 20 条且全为 `SUCCEEDED`，重复中奖发放数为 0。吞吐区间和延迟有运行间波动，单机 3 轮样本不足以把差异归因于 Redis；这些数字不得写成生产 QPS、Redis 优于 MySQL，或“Redis 使系统提速”。

早期 `draw-limiter-off-20260924.json` 与 `draw-limiter-redis-20260924.json` 是探索性冷连接试跑；`*-warmed-r1..r3-*` 虽有预热，但使用后续收紧 Redis/JDBC timeout 前的配置。文件全部保留为原始记录，但不纳入本次正式统计。正式三组为 `draw-limiter-*-warmed-r4-20260924.json`、`draw-limiter-*-final-r5-20260924.json`、`draw-limiter-*-final-r6-20260924.json`，均使用最终 timeout 配置和同一 HTTP 采样实现。

### 两档本机负载复测（2026-09-27）

同一台 12 逻辑处理器 Windows 主机上，对 100 请求/20 并发和 250 请求/50 并发分别在三次全新隔离环境复测。均为单用户认证即时抽奖、固定 50% 库存（概率 1）、Redis 限流关闭、单节点 Kafka、Worker 本地模拟履约。时延只统计并发 HTTP 批次，不含启动、登录、Worker 消费等待或清理。

| 请求 / 并发 | 重复次数 | 吞吐中位数 / 范围（请求/秒） | p50 中位数 / 范围（毫秒） | p95 中位数 / 范围（毫秒） | p99 中位数 / 范围（毫秒） |
|---|---:|---:|---:|---:|---:|
| 100 / 20 | 3 | 45.24 / 43.44–49.88 | 339.27 / 327.98–366.57 | 458.43 / 404.92–517.45 | 557.18 / 409.77–565.04 |
| 250 / 50 | 3 | 56.38 / 50.41–56.65 | 756.14 / 752.53–779.43 | 897.02 / 885.37–1051.86 | 1058.16 / 909.70–1328.80 |

两档各三轮均通过业务核对：100 请求档有 100 条抽奖结果/50 条中奖履约，250 请求档有 250 条抽奖结果/125 条中奖履约；每轮库存归零、中奖 Outbox=`SENT`、Worker=`SUCCEEDED`，重复履约数为 0。50 并发档的延迟明显高于 20 并发档，但这些单机样本不能单独定位原因或推导生产极限。六份原始逐请求数据：100/20 [R1](draw-load-100-20-r1-2026-09-27.json)、[R2](draw-load-100-20-r2-2026-09-27.json)、[R3](draw-load-100-20-r3-2026-09-27.json)；250/50 [R1](draw-load-250-50-r1-2026-09-27.json)、[R2](draw-load-250-50-r2-2026-09-27.json)、[R3](draw-load-250-50-r3-2026-09-27.json)。复现较高档示例：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -RequestCount 250 -Concurrency 50 -PrizeStock 125 `
  -EvidencePath 'docs/baseline/my-load-run.json'
```

这是受控本机负载样本，不是峰值容量或生产 QPS；单主机、单节点 Broker、单活动/单用户与模拟履约不代表多实例/多活动生产部署。P15 仍需更高负载、排队入口并发/积压观测、环境扩展与发布候选证据。

### 异步排队负载演练

已在 `scripts/smoke-draw-worker-cross-process.ps1` 增加 `-BenchmarkDrawQueue` 模式：并发提交队列请求，等待 `DrawQueued` Outbox 全部发布后记录 backlog，再启动新的 Campaign consumer 组；记录受理延迟/吞吐、消费与履约完成耗时，并核对队列终态、抽奖结果、库存、Outbox、Worker ledger 与重复履约。此模式要求 `-EnableRedisLimiter`，以启用真实 Redis queue admission guard；测试会把用户/活动/全局准入阈值设为本轮请求数 + 5、MySQL backlog 上限设为请求数 + 50，DB pool high-watermark 设为 1.0。它是明确参数化的本机负载场景，不代表产品默认限流容量。

2026-09-28 在 Windows 11（build 26200）、12 逻辑处理器、JDK 17.0.12、PowerShell 7.6.5、Docker Engine 29.8.0 上完成三档实测。每轮均使用全新、随机命名、无持久卷的 MySQL/Redis/Kafka 临时容器和独立 Campaign/Worker 进程；认证用户与活动为临时数据，履约为本地模拟。队列受理限制专设为本轮请求数 + 5、backlog 上限为请求数 + 50、DB pool high-watermark 为 1.0；一个全新 Campaign queue consumer 在全部 `DrawQueued` Outbox 发出后才启动，DB 恢复扫描延后 10 分钟。此组参数不等于产品默认值。

| 请求 / 并发 | 轮数 / 受理结果 | 吞吐中位数 / 范围（已受理/秒） | 受理 p50 中位数（毫秒） | 受理 p95 中位数 / 范围（毫秒） | 受理 p99 中位数（毫秒） | Outbox 发布等待中位数 | Consumer 启动至排空及履约完成中位数 | 从提交开始端到端中位数 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 / 20 | 3 / 300 of 300 | 63.68 / 61.54–67.28 | 100.81 | 157.56 / 140.99–168.78 | 187.94 | 8.93 s | 19.24 s | 29.55 s |
| 250 / 50 | 3 / 750 of 750 | 90.97 / 90.87–93.43 | 76.32 | 150.48 / 147.64–172.58 | 186.93 | 24.16 s | 30.94 s | 57.73 s |
| 500 / 100 | 3 / 1,498 of 1,500 | 94.18 / 94.05–101.63 | 80.05 | 142.63 / 137.14–157.81 | 172.09 | 49.62 s | 42.68 s | 97.24 s |

100/20 和 250/50 六轮均全量受理、没有 HTTP 拒绝；500/100 三轮合计 1,498/1,500 受理，另 2 次返回 503/PFX-9001。三档每个已受理队列项均 `COMPLETED`、无 pending；draw rows 和 DrawQueued Outbox=`SENT` 等于受理数，库存归零。中奖履约分别为 50、125、250 次/轮；Worker ledger 全部 `SUCCEEDED`，无重复履约、无人工补偿任务。原始逐请求 JSON：100/20 [R1](draw-queue-load-100-20-r1-2026-09-28.json)、[R2](draw-queue-load-100-20-r2-2026-09-28.json)、[R3](draw-queue-load-100-20-r3-2026-09-28.json)；250/50 [R1](draw-queue-load-250-50-r1-2026-09-28.json)、[R2](draw-queue-load-250-50-r2-2026-09-28.json)、[R3](draw-queue-load-250-50-r3-2026-09-28.json)；500/100 [R1](draw-queue-load-500-100-r1-2026-09-28.json)、[R2](draw-queue-load-500-100-r2-2026-09-28.json)、[R3](draw-queue-load-500-100-r3-2026-09-28.json)。这些历史运行未记录服务端保护来源；复现脚本现会校验结构化原因日志。

#### 多用户/多活动排队演练（单轮补充，不并入上表）

压测脚本支持 `-QueueBenchmarkUserCount` 和 `-QueueBenchmarkCampaignCount`：在随机隔离数据库创建临时 USER 并分别登录，按请求序号轮转身份和活动；SQL 对活动集合聚合，逐请求证据仅记录匿名 `UserSlot`/`CampaignSlot`。Redis 用户/活动级阈值按对应维度均分请求量加 5，全局阈值按总请求量加 5，避免身份/活动分布自身触发本轮保护。

500 请求/100 并发/20 用户/单活动：每用户恰好 25 次；500/500 受理、0 次 503，吞吐 95.13 次/秒、p95 145.25ms，consumer 启动 backlog=500，消费排空及模拟履约 42.63s，端到端 100.61s。500 条全部 `COMPLETED`，结果和 DrawQueued Outbox 对账相等，库存归零，Worker 250/250 成功；无重复履约或人工补偿。原始证据：[20-user JSON](draw-queue-load-500-100-20-users-2026-09-28.json)。

100 请求/20 并发/10 用户/5 活动：每用户 10 次、每活动 20 次尝试；99/100 受理，1 次 503/PFX-9001 明确为 `MYSQL_CONNECTION_POOL_PRESSURE`，吞吐 99.90 次/秒、p95 80.98ms。受理的 99 条全部 `COMPLETED`，结果与 DrawQueued Outbox 均为 99，无 pending；总库存归零，Worker 50/50 成功，无重复履约/人工补偿。原始证据：[10-user 5-campaign JSON](draw-queue-load-100-20-10-users-5-campaigns-2026-09-28.json)。

这两轮是严格轮转合成身份、单 consumer 的单轮演练；第二轮观察到连接池保护拒绝，均不与历史单用户重复轮合并或比较成性能提升。它们验证多身份/多活动下的准入和集合对账，不代表真实流量分布、多 Campaign 实例扩展或生产容量。复现多用户/多活动样例：

复现命令：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -BenchmarkDrawQueue -EnableRedisLimiter -RequestCount 100 -Concurrency 20 -PrizeStock 50 `
  -QueueBenchmarkUserCount 10 -QueueBenchmarkCampaignCount 5 `
  -EvidencePath 'docs/baseline/draw-queue-load-100-20-10-users-5-campaigns-2026-09-28.json'
```

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -BenchmarkDrawQueue -EnableRedisLimiter -RequestCount 250 -Concurrency 50 -PrizeStock 125 `
  -EvidencePath 'docs/baseline/draw-queue-load-250-50-r1-2026-09-28.json'
```

脚本也验证了有意触发容量保护的路径。历史单轮（100 次/20 并发、backlog limit=5）观察到 5 条受理、95 条 503；后来加上原因观测后，同配置审计轮为 9 条受理、91 条 503，原因均为 `MYSQL_BACKLOG_LIMIT`。9 条均 `COMPLETED`，9 条结果与 Outbox 对账，库存归零、5 条中奖履约 `SUCCEEDED`。backlog 实际在 consumer 启动时为 9，超过配置阈值 5，证明这是基于快照的 best-effort shedding 而非硬上限；并发检查后的竞态允许一定超量。该场景只验证可分类拒绝和受理子集正确性，不是吞吐基准。历史记录：[draw-queue-overload-guard-100-20-2026-09-28.json](draw-queue-overload-guard-100-20-2026-09-28.json)；原因审计：[draw-queue-overload-guard-reason-audit-5-stock-2026-09-28.json](draw-queue-overload-guard-reason-audit-5-stock-2026-09-28.json)。第一次审计尝试配置 50 库存但只受理 6 条，触发了测试期望不匹配且未生成证据文件；校正为 5 库存后重跑通过。命令：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -BenchmarkDrawQueue -EnableRedisLimiter -RequestCount 100 -Concurrency 20 -PrizeStock 1 `
  -QueueBacklogLimit 5 -RequireQueueCapacityRejections `
  -EvidencePath 'docs/baseline/draw-queue-overload-guard-100-20-2026-09-28.json'
```

一个早期、未完成的探索运行曾观察到 249/250 受理及一个 503/PFX-9001；当时旧脚本因要求全量 202 而在排空/对账前退出，不纳入上述结果。脚本随后增加 `ErrorCode` 记录、容量拒绝统计、受理子集排空对账以及可选低 backlog/必须拒绝参数；后续三轮 250/50 完整运行均 250/250、0 次拒绝。所有其它非 202 响应仍令演练失败。

保护原因审计还额外执行一轮 500/100：427/500 受理、73 次 503/PFX-9001 均由 `MYSQL_CONNECTION_POOL_PRESSURE` 触发；吞吐 162.04 次受理/秒、p50/p95/p99 为 59.01/95.86/110.25ms、Outbox 发布等待 47.32s、consumer 启动至排空及履约 39.69s、端到端 89.68s。427 条全部完成，结果/Outbox/Worker 对账通过，库存归零（见 [原因审计 JSON](draw-queue-load-500-100-reason-audit-2026-09-28.json)）。这轮单独报告，不混入上表三轮中位数，展示相同本机档位也会有明显波动。

队列受理 p50/p95/p99 统计本轮全部 HTTP 响应；吞吐用已受理数量除以并发 HTTP 批次时间。Consumer 完成耗时包括 consumer 进程启动/健康检查和积压处理；端到端时间还包括 HTTP 批次及等待 Outbox 发布。队列工作量与即时抽奖不同，不能直接按两张表对比 Redis 或同步/异步架构的优劣。所有这些仍是受控单机样本，不是峰值容量或生产 QPS；单主机、单节点 Kafka、单 Campaign queue consumer 和模拟履约不代表多实例生产部署，也不能证明数千或数万人同时参与时的容量。重复负载历史轮为单用户；新增多用户/多活动用例仅各一轮、合成身份严格轮转。backlog/连接池阈值是非原子压力快照的 best-effort shedding，不是硬容量上限；精确语义见 [高峰队列规格](../../SPEC-high-peak-queue.md)。下一步完成产品 Compose 运行态验收或增加有目的的重复采样。

#### k6 开放模型持续到达率（J2，入口和基准复测完成；50 RPS 边界探索未达性能门槛）

`scripts/perf/draw-queue-arrival-rate.js` 使用 `constant-arrival-rate` 开放模型；外层隔离脚本在随机临时 MySQL/Redis/Kafka 与独立 Campaign/Worker 进程中运行它。与旧批量队列模式不同，Campaign 的 Kafka queue consumer 和 Worker 从预热前就持续在线。场景先以 `min(5,目标速率)` 请求/秒预热 30 秒，再按给定目标速率发压；k6 保存详细聚合 summary，隔离脚本另保存业务对账证据。随机 JWT 暂存于 Windows 临时目录，退出时清理；不会写入仓库证据。除 attempts、admissions、容量拒绝、错误、`dropped_iterations`、入口与受理至终态延迟、容器 CPU/内存外，现在每5秒记录活动队列数/最老年龄、Hikari、Performance Schema 锁等待点采样、Campaign/Worker JVM 进程 CPU、队列处理 outcome 计数/Timer、抽奖事务与低基数数据库分段计时。队列 drain 期间也保留待处理数/年龄变化，120秒内排空后核对队列/结果/库存/Outbox/Worker。JVM CPU 是 Windows 进程累计 CPU 时间换算的“单个逻辑核心百分比”，不是整机 CPU 利用率；锁等待是点采样，可能错过瞬时等待。

已验证 k6 v2.3.0 官方 Windows amd64 包 SHA-256，并完成小型功能烟测。2026-09-29 在同一台 Windows 主机完成 25 RPS×60 秒单变量探索：队列消费者并发 1、Outbox 间隔 5 秒时端到端 P95 约127.5秒且120秒后仍有321条 pending；将隔离基准的 Outbox 间隔设为1秒后并发1/2/4对应 P95 约36.5秒/11.3秒/2.65秒。并发4这轮短测入口 P95/P99 为37.8/56.0ms、无 dropped、排空和业务对账通过，因此只选为待复测候选配置；并发1/2结果未达5秒端到端 P95 门槛。**以上每档仅一轮60秒，不是稳定容量或生产 QPS。**逐轮原始数据、边界、限制和面试解释见[项目难点与面试深挖手册](../interview/prizeflux-project-deep-dive.md)。

候选配置按正式门槛完成三轮复测：25 RPS×300秒、Outbox 1秒、队列消费者并发4。R1/R2/R3 均通过：受理7501/7500/7500、dropped均为0，入口 P95/P99 分别40.10/98.68ms、33.71/44.31ms、34.41/56.38ms，受理至终态 P95 分别3.04/2.52/2.57秒；均在120秒内排空且 MySQL/Outbox/Worker 对账通过。三轮为单合成用户、单活动、奖品库存1、同机单 Kafka broker 的本机样本，只能称“本机该场景 25 RPS×5分钟三轮验证通过”，不能称生产容量。

50 RPS×60秒首次单用户/单活动、池10诊断 FAIL：3001 次测量尝试、2996 次受理、5 次 `MYSQL_CONNECTION_POOL_PRESSURE` 保护拒绝、0 dropped；入口 P95/P99 137.9/372.8ms、受理至终态 P95 56.0秒、结束后57.8秒排空、最老年龄58秒，最终对账通过。此后调高 Hikari 上限至20，20用户/单活动/消费者4 的终态 P95 仍为51.409秒；消费者4改8仍为52.494秒。接着用20用户、10活动、总库存10（即每活动1库存）、池20、消费者4做基数诊断，两轮终态 P95 分别51.895秒和50.694秒，均无 dropped、全数受理/最终对账正确，排空约53.49秒和48.86秒，性能门槛仍失败。最后一轮原始记录为 [多活动分段观测报告](draw-queue-arrival-50rps-60s-relay-1s-consumer-4-20users-10campaigns-pool20-segments-2026-09-29.json) 和 [k6 summary](draw-queue-arrival-50rps-60s-relay-1s-consumer-4-20users-10campaigns-pool20-segments-2026-09-29.k6-summary.json)。

最后一轮 5 秒采样看到处理速率约26次/秒，而到达率为50 RPS，活动队列峰值约1447；Hikari活跃峰值8/20、等待0，MySQL锁等待点采样峰值1，Campaign JVM进程CPU均值约59%、峰值144%单核，Campaign MySQL容器CPU峰值约58%。`DrawApplicationService` 数据库段 Timer 平均约1.36～2.90ms，事务 Timer 均值约29.86ms；采样过程计时约89ms/次。终态 P95 含预热请求。现有证据削弱了“连接池饱和/单条慢 SQL”为主因的解释，但未排除同步 Outbox/Kafka 确认、listener等待或其他调度开销，不能仅凭点采样断定根因。暂停升压，下一步审阅 Outbox 发布与每条 Kafka record 同步提交路径，形成假设后再做一次有针对性的优化和同条件前后比较。

先做 1 次小型功能烟测（需要 JDK 17、已构建 Campaign/Worker JAR、Docker Engine 和 k6）：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -BenchmarkDrawQueue -EnableRedisLimiter `
  -QueueArrivalRate 1 -QueueArrivalDurationSeconds 10 -PrizeStock 1 `
  -EvidencePath 'docs/baseline/draw-queue-arrival-smoke.json'
```

禁止复用已有证据文件名。每一轮都在同一台 Windows 主机运行压测器和服务，期间的 Docker/数据库监控查询开销也要计入限制。容器最高资源使用率不是容量结论；本机结果不能外推生产 QPS 或 HA。

## 2026-09-30 Kafka 诊断复测入口（尚未执行）

已有 50 RPS 失败档不改写。新版本采样补齐 Outbox 总耗时/创建到确认、Kafka enqueue/ack-wait 两段、每个 consumer client 的 commit latency/total/rate 和排队 topic/group committed lag；缺失/非有限值保留未知。原生 commit latency 单位毫秒，Timer 单位秒。详细口径见[观测合同](../architecture/observability.md)。本机真实 Embedded Kafka/Prometheus 导出验证通过，但不代表 local-full 性能验证或 MySQL 账本验收。

前置：Docker Linux Engine 可用，JDK17、k6 可用，Campaign/Worker JAR 与当前源码一致。Campaign 已于本轮重新 package。先以1 RPS小型烟测确认采样，再复现旧失败档；为两个实验各选择不存在的证据文件名，不覆盖历史数据。复测命令（在仓库根目录，以 PowerShell 7 运行；k6不在PATH时另传`-K6Path`）：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -BenchmarkDrawQueue -EnableRedisLimiter `
  -QueueArrivalRate 50 -QueueArrivalDurationSeconds 60 `
  -OutboxRelayDelaySeconds 1 -QueueConsumerConcurrency 4 `
  -QueueBenchmarkUserCount 20 -QueueBenchmarkCampaignCount 10 `
  -QueueBenchmarkHikariMaximumPoolSize 20 -PrizeStock 10 `
  -EvidencePath 'docs/baseline/draw-queue-arrival-50rps-60s-kafka-diagnostics-r1-2026-09-30.json'
```

最初接线时Docker不可用；2026-09-30用户启动Docker后，重新构建Campaign/Worker并实际运行。小型隔离接线烟测PASS，见 [smoke原始记录](draw-queue-arrival-kafka-diagnostics-smoke-2026-09-30.json)。同条件50 RPS复测性能FAIL、业务对账PASS，见 [复测原始记录](draw-queue-arrival-50rps-60s-kafka-diagnostics-r1-2026-09-30.json) 和 [k6摘要](draw-queue-arrival-50rps-60s-kafka-diagnostics-r1-2026-09-30.k6-summary.json)：3001次全部受理、0 dropped，入口P95/P99=44.44/74.92ms，终态P95=50.672秒，pending峰值1432；含预热3152条队列均完成，10次中奖/履约、库存0、无重复，120秒内排空。

诊断窗口45.3～95.1秒（预热后负载窗口）：发布增量1318条/49.8秒=26.47条/秒，broker ack等待增量均值2.53ms、Outbox发布增量均值17.61ms。16个group committed lag缓存样本为0～18，发送与完成累计数相近；10秒lag刷新和约5秒抓取并非连续监测，不能宣称lag连续不超过18。Hikari等待采样为0。源码每批50条串行发布后固定等待1秒，`50/(50*0.01761+1)≈26.6/秒`，与实测接近。这支持优先调查Relay批间等待/串行总成本，尚未证明唯一根因，更不能由均值宣称Kafka commit没有任何成本。下一步在租约/围栏与中断恢复合同内做单变量对照，保留AckMode；没有实施J3优化，不升档或外推生产容量。

本次证据新增构建JAR、编排脚本和采样模块SHA-256及源码commit/dirty数量。指标解析已拆入 `scripts/perf/CampaignRuntimeMetrics.psm1`，离线合同检查入口为 `scripts/perf/test-runtime-metrics.ps1`；主编排脚本仍偏大，债务及边界见 [可维护性约束](../architecture/maintainability.md)。两次最终运行只创建并清理自身隔离环境，保留用户原有Kafka；没有修改产品卷或调用真实模型。

## 2026-09-30 J3 长测补充诊断

2026-09-30后续10ms/consumer8、50 RPS×300秒单变量诊断性能FAIL且劣化：[10ms原始记录](draw-queue-arrival-50rps-300s-relay-10ms-consumer-8-r1-2026-09-30.json)。15001全受理、0 dropped，入口P95/P99=72.03/111.47ms，终态P95=72.756秒，pending峰值3175，StopThresholdExceeded=true；120秒内排空，含预热15152条完成/结果/通知，10条中奖/成功履约、库存0、无重复。100.3～295.5秒负载窗口发布39.12条/秒、完成39.10条/秒，Outbox单条发布均值24.72ms、ack均值1.50ms。更短等待没有单调收益，但本机单轮不足以认定轮询压力是唯一根因。停止调小等待或升档；新增Relay repository分段供后续定位，这两轮旧JAR没有新分段数据，不回填JSON。生产配置不变。

### 2026-09-30 J3 单变量Relay调度对照

新增benchmark参数 `-OutboxRelayDelayMilliseconds`（0代表沿用旧秒参数，显式非零范围1～60000）；只允许benchmark使用，与显式秒参数互斥。报告保存实际秒/毫秒值，旧秒参数调用保持兼容。实际命令沿用上一节50 RPS配置，仅以 `-OutboxRelayDelayMilliseconds 100` 替换 `-OutboxRelayDelaySeconds 1`。生产默认调度未修改。

第一轮100毫秒/consumer4：3000/3000受理、0 dropped；入口P95/P99=43.89/74.77ms，终态P95=14.159秒，pending峰值480，120秒内排空，含预热3150条队列/结果/通知全部对账，10次中奖及成功履约、库存0、无重复。相比1秒/consumer4单轮终态P95=50.672秒及pending1432有改善，但仍性能FAIL。41.3～95.7秒增量发布50条/秒、完成42.68条/秒、broker ack均值2.16ms、处理均值91.24ms；group committed lag缓存样本最高400。发布限制缓解后消费侧积压出现，下一单变量对照固定100毫秒、consumer4→8；不能把原有1秒档消费者扩容无收益的结果直接套到新配置。

原始记录：[100毫秒consumer4](draw-queue-arrival-50rps-60s-relay-100ms-r1-2026-09-30.json)。这是短测候选探索，不是稳定50 RPS×5分钟成绩，也不是并发发送代码改造。更短空闲等待增加空载数据库查询频率，产品配置改变前仍须考察资源/故障与回退；没有扩大batch或修改lease/ack/权威事务。

固定100毫秒，仅consumer4→8的第二轮短测PASS：[100毫秒consumer8原始记录](draw-queue-arrival-50rps-60s-relay-100ms-consumer-8-r1-2026-09-30.json)。3001/3001受理、0 dropped；入口P95/P99=61.81/93.22ms，终态P95=4.439秒，pending峰值196，120秒内排空，含预热3151条全部完成/结果/通知对账，10条中奖/成功履约、库存0、无重复。该时点只验证单轮60秒，后续consumer12的三轮长测及候选故障恢复见下文。生产默认值不变，不能把本轮短测单独当生产容量。

同日100ms/consumer8、50 RPS×300秒第一轮性能FAIL：[长测原始记录](draw-queue-arrival-50rps-300s-relay-100ms-consumer-8-r1-2026-09-30.json)。15001全部受理、0 dropped，入口P95/P99=57.33/92.41ms，终态P95=29.884秒，pending峰值1241；流入结束后30.12秒排空，含预热15151条均完成，10次中奖/Worker成功、库存0、无重复。101.2～294秒窗口发布47.57条/秒，Outbox单条发布均值18.56ms，lag缓存样本最大18，发送与完成数接近；更长的101.2～336.7秒窗口混合了负载结束与排空，不用于稳定负载速率推断。CPU/锁/池采样是点采样，不足以证明唯一根因。停止重复失败候选；下一诊断仅改100ms→10ms，生产默认未变，性能目标仍未完成。

## 2026-09-30 新数据库分段与有界并发候选

新分段完整运行接线 [smoke](draw-queue-outbox-db-stage-smoke-2026-09-30.json) PASS。串行100ms/consumer8、50 RPS×60秒 [诊断](draw-queue-outbox-db-stage-50rps-60s-2026-09-30.json) PASS，3001受理、终态P95=1.631秒。40.4～93.3秒增量markSent 2640次/40.59秒/均值15.37ms，claim59次均值20.55ms（按批）、reclaim58次均值1.18ms；publish均值17.34ms、broker ack1.82ms。不能由repository调用计时断言唯一SQL或磁盘根因。

按既定J3实现可选有界并发，每轮最多claim并发数，一波join再领取，默认串行。新候选 [Relay并行8短测](draw-queue-outbox-parallel-8-50rps-60s-r1-2026-09-30.json) PASS：50 RPS×60秒，3000全受理、0 dropped，入口P95/P99=35.58/49.46ms，终态P95=425.98ms、pending峰值13，停止流入120秒内排空，含预热3150条对账、10次中奖/成功履约、库存0、无重复。命令在既有同配置命令上增加 `-OutboxRelayParallelism 8`；其余保持100ms/consumer8/20用户/10活动/池20/库存10。报告写configured batch50及effective claim8；并发度与安全领取限制作为同一个实现候选，不称只增加线程且批次完全不变。新分段串行与并行各单轮，不足以消除宿主负载/短测波动影响。

整波15秒超时/中断会停止后续领取，取消不等于强杀JDBC；未决租约由其他实例或重启后回收，逐事件token保护保留。候选稳定长测和跨进程Broker故障仍待验证，生产默认未改。

Relay8/consumer8/100ms的 [50 RPS×300秒R1](draw-queue-outbox-parallel-8-50rps-300s-r1-2026-09-30.json) 性能FAIL：15000尝试/14999受理，1次MYSQL_CONNECTION_POOL_PRESSURE拒绝、0 dropped，入口P95/P99=69.77/110.38ms，终态P95=17.032秒，pending峰值600，停止流入后16.996秒排空；含预热15149条完成/结果/通知全部对账，10条中奖/Worker成功、库存0、无重复。104.4～299.8秒发布49.46/完成47.46条/秒，Hikari活跃峰值20/20、等待峰值2；reclaim1219次均值1.21ms、claim1220次均值28.77ms（按批）、markSent9665次均值26.14ms（按事件）。并行Timer时间相互重叠，不可把累计耗时相加当墙钟时间。

该窗口8consumer分配2/2/2/2/1/1/1/1分区，commit增量速率前4约7.76～7.79次/秒、后4约3.85～4.34次/秒；group lag随发布与完成差增长，支持检查分区分配/消费成本，不是唯一根因证明。下一单变量consumer8→12，其他保持；暂不扩大池或升RPS。短测PASS与此长测FAIL均保留，不将短测作为稳定容量。

### consumer12候选三轮长测（2026-09-30）

固定 Relay8/100ms、连接池20、20用户/10活动/库存10，R1/R2/R3 的50 RPS×300秒都通过脚本门槛：受理比例至少99%、保护拒绝不超过1%、0 dropped、终态P95≤5秒、120秒内排空且账本对账通过。

| 轮次 | 测量尝试/受理 | 保护拒绝 | 入口P95/P99 | 终态P95 | pending峰值 | 排空 | 结果 |
|---|---:|---:|---:|---:|---:|---:|---|
| R1 | 15000/14998 | 2（原因未留证） | 79.86/120.06ms | 3.878秒 | 209 | 483ms | PASS |
| R2 | 15001/14995 | 6 | 74.02/162.06ms | 2.941秒 | 167 | 459ms | PASS |
| R3 | 15001/15000 | 1 | 63.44/106.78ms | 0.813秒 | 74 | 483ms | PASS |

三轮包含预热，各自完成15145～15150条队列；结果数及 `DrawQueued` Outbox=`SENT` 均与受理数相同，10次中奖与模拟履约成功、库存归零，无重复或人工处理。R2/R3服务日志原因汇总分别是6/1次 `MYSQL_CONNECTION_POOL_PRESSURE`；日志统计覆盖准备、预热和测量，HTTP测量拒绝没有逐请求ID关联，因此只能作为阶段范围内的原因信号。R1的旧报告没有原因数据，未回填。单机单Broker、模拟履约的本机候选数据，不代表生产或万人容量；生产默认parallelism1/5秒未改。逐轮原始 JSON 与 k6 summary：`draw-queue-outbox-parallel-8-consumer-12-50rps-300s-r{1,2,3}-2026-09-30.json`。

### 候选配置 Kafka 故障与重试退避

候选故障首次运行发现：100ms Relay间隔配合“失败后立即重新可领取”会在Broker重启完成前耗尽默认3次 Outbox 尝试并进入 `DEAD`。那次脚本在异常路径没有落 JSON，脱敏控制台日志显示发送超时和 `DEAD_LETTERED`；报告不伪造为结构化原始证据。修复后，Outbox失败按既有 `available_at` 写入指数退避：2秒起、封顶30秒，更新仍受 lease token 围栏，无需数据库迁移。max-attempts仍为3，持续过久的故障会进入死信，需运营处理。

更新后的 Campaign JAR 使用 Relay8/100ms、consumer12、池20重新注入停机。隔离Broker恢复约8.09秒；队列在Broker不可用期间保持 `QUEUED:0`，通知恢复后为 `SENT:3`，然后启动consumer，最终队列 `COMPLETED`、抽奖结果1条、库存0、DrawQueued Outbox 1条。该故障演练使用单请求、单Broker和无持久卷，不证明多节点HA或长时间故障。证据：[候选 Kafka outage R2](draw-queue-outbox-parallel-8-kafka-outage-r2-2026-09-30.json)。

## 依赖故障演练

各依赖故障均单独注入到脚本用随机名称创建的临时容器，恢复后验证抽奖/发奖闭环；每份原始记录标明自己的运行次数与环境。旧 Redis/MySQL 单次记录保留，Kafka 在 2026-09-27 另做了一次复验。

| 故障 | 观测结果 | 恢复与正确性 |
|---|---|---|
| Redis 限流依赖停止 | 抽奖入口在约 1.11 秒后固定返回 503；故障期间没有抽奖结果、Outbox 事件或库存扣减 | Redis 容器重启；之后抽奖成功，Outbox `SENT`、Worker `SUCCEEDED` |
| Campaign MySQL 停止 | 抽奖入口在约 3.14 秒后返回通用 500；恢复数据库后检查到没有抽奖结果、Outbox 事件或库存扣减 | 使用同一个仅含临时可写层的 MySQL 容器恢复（未挂载 named volume）；随后抽奖成功并走完 Worker 链路 |
| Kafka Broker 停止 | 中奖事务已提交；Outbox 发布失败后为 `PENDING`，尝试次数为 1，没有被标记 `SENT` 或丢弃 | Broker 在约 10.3 秒内重新监听；Outbox 最终 `SENT`，独立 Worker 账本最终 `SUCCEEDED`，无重复发放 |

Redis/Kafka/MySQL 单次故障证据：

- [Redis outage](fault-redis-outage-bounded-timeout-20260924.json)
- [Campaign MySQL outage](fault-campaign-mysql-outage-20260924.json)
- [Kafka outage and recovery](fault-kafka-outage-final-20260924.json)

2026-09-27 使用 `-InjectKafkaOutage` 对发奖链路更新复验：临时单节点 Kafka 停机期间，Campaign 已提交的中奖结果保留为 `PENDING` Outbox（发布尝试 1 次）；重启后 Broker 约 8.9 秒重新监听，Outbox 最终 `SENT`，Worker 最终仅有 1 条 `SUCCEEDED` 账本，库存归零、无重复履约记录。原始记录：[kafka-outage-recovery-2026-09-27.json](kafka-outage-recovery-2026-09-27.json)。

随后以 `-InjectDrawQueueKafkaOutage` 验证排队开奖通知：Kafka 停机时 HTTP 已受理请求仍保存在 MySQL，队列为 `QUEUED`、`DrawQueued` Outbox 为 `PENDING`；恢复后先确认通知已是 `SENT`，且队列仍 `QUEUED:0`，再启动全新的 consumer group。新组约 12.6 秒内消费积压，队列转为 `COMPLETED`，只有 1 条抽奖结果、库存归零、Outbox 为 `SENT`；Broker 从停机到重新监听约 10.3 秒。场景将 MySQL 周期扫描延后 10 分钟，避免扫描兜底掩盖 Kafka 消费结果。演练发现新组默认 `latest` 会跳过已积压通知，已将 Campaign 默认改为 `earliest`。最终原始记录：[draw-queue-kafka-recovery-final-2026-09-27.json](draw-queue-kafka-recovery-final-2026-09-27.json)。早期探索性记录 [draw-queue-kafka-recovery-2026-09-27.json](draw-queue-kafka-recovery-2026-09-27.json) 保留供追溯，但最终文件才证明“新组启动前已有积压仍被消费”。

上述两个故障恢复演练各自使用单 Broker、单 Campaign consumer、一次请求和无持久卷临时容器；不验证多消费者 group rebalance、多 Broker HA 或容量。之后另做了双消费者重平衡演练：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -InjectDrawQueueKafkaRebalance `
  -EvidencePath 'docs/baseline/draw-queue-kafka-rebalance-2026-09-27.json'
```

单节点 Kafka 的 12 个队列 topic 分区在两份独立 Campaign JVM 加入同一个 group 后由 generation 2 重平衡至 generation 3，两实例各获 6 个分区。一个活动上的 24 个唯一 request key 横跨分区消费；恢复扫描延迟 10 分钟，避免扫描兜底。最终 24 条队列均 `COMPLETED`，对应 24 条抽奖结果和 24 条 `DrawQueued` Outbox=`SENT`，库存归零。完整原始记录：[draw-queue-kafka-rebalance-2026-09-27.json](draw-queue-kafka-rebalance-2026-09-27.json)。这证明的是本机双消费者分区重平衡和业务对账，不是多活动压力、多 Broker HA 或容量证据。

同日重跑队列应用/消费者集成用例：`DrawApplicationIntegrationTest` 10 项、`DrawQueueKafkaConsumerIntegrationTest` 2 项通过。该结果只验证这些自动化合同，不替代隔离进程故障/分区演练。

### AI 进程停止时抽奖可用性（2026-09-27）

以 `deploy/run-isolated-process-smoke.ps1` 在独立 Compose 项目中先启动并验证 Campaign、Worker、Python Agent、legacy Java AI 与 Vue；Compose 源 MySQL/Redis/Kafka 没有持久卷。完成 AI 合同验证后停止两个 AI 进程，并确认它们的 loopback HTTP 端口已关闭。AI 进程不可用时，Campaign 仍成功完成一笔即时中奖与一笔排队中奖。为避免 MySQL 恢复扫描兜底，排队扫描延迟 10 分钟；抽奖结果完成时 `DrawQueued` Outbox 为 `SENT`，队列消费者经 Kafka 完成，MySQL 中恰有两条抽奖结果且库存由 2 降为 0。随后停写，对已迁移的 Campaign/Worker/Agent 三 schema 执行 `mysqldump --single-transaction`，流式还原到新建且带随机 run label 的 MySQL named volume；恢复库逐项核验两条 Campaign 抽奖/完成队列/库存为 0、Worker 两条 `SUCCEEDED` 履约账本及 Agent 至少一条 `COMPLETED` run。只在确认卷名和 label 与本轮一致后清理测试卷。完整 smoke 同时通过三套迁移/权限、USER/JWT、Agent owner/SSE 等检查。命令：`$env:JAVA_HOME='D:\java\jdk-17'; .\deploy\run-isolated-process-smoke.ps1`。最终运行日志目录：`%TEMP%\prizeflux-smoke-fa5c22c2db`；账号、Token、密码和 dump 正文未输出到该日志。本实验验证逻辑备份能恢复到新卷，不验证产品既有持久卷的在线备份策略；也不代表真实模型调用、生产部署或高并发容量。

### 产品 MySQL 命名卷在线逻辑备份工具（2026-09-27）

新增 `deploy/backup-product-mysql.ps1`：按 Compose project/service 标签定位唯一运行中的 MySQL，要求容器 healthy、数据挂载卷名及卷标签都与期望产品卷匹配；检查 Campaign/Worker/Agent 三 schema 和业务表 InnoDB 后，使用 `mysqldump --single-transaction --quick` 输出 SQL。目标必须在仓库外、不会覆盖旧文件；脚本只打印路径/字节数/SHA-256，不打印凭据或 SQL 内容。用 `scripts/smoke-backup-product-mysql.ps1` 在两个随机命名、`--network none` 的隔离 MySQL named volumes 上验证导出，再恢复到新卷并核对 `snapshot-ok` 样例行，全部通过。首次测试暴露并修复 PowerShell 单元素数组索引问题；最终重跑退出码 0，失败回合的随机容器/卷已检查为清理完毕。本机尚无 `prizeflux_product_mysql_data`，所以并未导出或更改真实产品数据。

边界：这是本地手动明文 SQL 逻辑快照；目录加密、加密静态数据、自动排程、异地副本、保留期、密钥保管及 binlog/PITR 未实现。生产数据只能保存到受保护目录，不可提交或分享。恢复测试只用临时样例数据，真正产品库恢复仍须先写入新的隔离卷并做业务对账。

Worker 使用显式 `local-full` 模拟履约适配器，只证明事件消费、持久化幂等与本地成功状态，不向真实供应商发货、退款或调外部服务。云端、多副本 Kafka/Redis、远程 CI、真实供应商和公网部署均未由这些试验证明。

## 重现

在仓库根目录、Docker Engine 可用时执行。脚本为服务动态选择 loopback 空闲端口，并在启动容器前复查占用；若选中的端口被并发进程抢占会安全失败，不会停止其他服务。证据文件必须选一个新的 `docs/baseline/*.json` 路径，避免覆盖既有原始数据。

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17'
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -RequestCount 40 -Concurrency 10 -PrizeStock 20 `
  -EvidencePath 'docs/baseline/my-run.json'

.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -RequestCount 40 -Concurrency 10 -PrizeStock 20 -EnableRedisLimiter `
  -EvidencePath 'docs/baseline/my-run-redis.json'
```

依赖故障命令分别使用 `-RequestCount 1 -Concurrency 1 -PrizeStock 1`，并选择一个故障开关：`-EnableRedisLimiter -InjectRedisOutage`、`-InjectCampaignMySqlOutage`、`-InjectKafkaOutage` 或 `-InjectDrawQueueKafkaOutage`。脚本会拒绝非 `docs/baseline` 路径、拒绝覆盖已有文件，并在结束时清理自己创建的服务和恢复原环境变量。
