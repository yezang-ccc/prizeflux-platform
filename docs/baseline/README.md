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

## 依赖故障演练

每项各执行一次，故障只施加到脚本用随机名称创建的临时容器，恢复后验证抽奖/发奖闭环。结果文件是原始记录。

| 故障 | 观测结果 | 恢复与正确性 |
|---|---|---|
| Redis 限流依赖停止 | 抽奖入口在约 1.11 秒后固定返回 503；故障期间没有抽奖结果、Outbox 事件或库存扣减 | Redis 容器重启；之后抽奖成功，Outbox `SENT`、Worker `SUCCEEDED` |
| Campaign MySQL 停止 | 抽奖入口在约 3.14 秒后返回通用 500；恢复数据库后检查到没有抽奖结果、Outbox 事件或库存扣减 | 使用同一个临时 MySQL 容器与卷恢复；随后抽奖成功并走完 Worker 链路 |
| Kafka Broker 停止 | 中奖事务已提交；Outbox 发布失败后为 `PENDING`，尝试次数为 1，没有被标记 `SENT` 或丢弃 | Broker 在约 10.3 秒内重新监听；Outbox 最终 `SENT`，独立 Worker 账本最终 `SUCCEEDED`，无重复发放 |

Redis/Kafka/MySQL 单次故障证据：

- [Redis outage](fault-redis-outage-bounded-timeout-20260924.json)
- [Campaign MySQL outage](fault-campaign-mysql-outage-20260924.json)
- [Kafka outage and recovery](fault-kafka-outage-final-20260924.json)

Worker 使用显式 `local-full` 模拟履约适配器，只证明事件消费、持久化幂等与本地成功状态，不向真实供应商发货、退款或调外部服务。云端、多副本 Kafka/Redis、远程 CI、真实供应商和公网部署均未由这些试验证明。

## 重现

在仓库根目录、Docker Engine 可用且本机端口空闲时执行。证据文件必须选一个新的 `docs/baseline/*.json` 路径，避免覆盖既有原始数据。

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17'
.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -RequestCount 40 -Concurrency 10 -PrizeStock 20 `
  -EvidencePath 'docs/baseline/my-run.json'

.\scripts\smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17' `
  -RequestCount 40 -Concurrency 10 -PrizeStock 20 -EnableRedisLimiter `
  -EvidencePath 'docs/baseline/my-run-redis.json'
```

依赖故障命令分别使用 `-RequestCount 1 -Concurrency 1 -PrizeStock 1`，并选择一个故障开关：`-EnableRedisLimiter -InjectRedisOutage`、`-InjectCampaignMySqlOutage` 或 `-InjectKafkaOutage`。脚本会拒绝非 `docs/baseline` 路径、拒绝覆盖已有文件，并在结束时清理自己创建的服务和恢复原环境变量。
