# PrizeFlux 简历与面试摘要（当前可证据范围）

## 一句话介绍

PrizeFlux 是一个 Java 17/Spring Boot 营销抽奖与 Agent Harness 学习项目：抽奖主链用 MySQL 保证库存、业务次数、幂等结果和 Outbox 的事务一致性；Kafka 将中奖事件交给独立 Worker；自建 Java Harness 通过服务端授权的只读工具查询活动。

## 可放入简历的项目描述

- 设计并实现活动抽奖核心链路，使用 MySQL 事务、条件库存扣减、用户活动次数和请求幂等记录，避免并发超卖及重放重复扣减；集成测试以 10 轮、每轮 8 个并发请求争抢 1 份库存，稳定得到 1 次中奖、7 次售罄，库存归零且 Outbox 只写入 1 条。
- 实现 Redis Lua 固定窗口用户限流，限流身份取自服务端 JWT；通过两个独立限流器实例的 80 路并发验证共享窗口精确放行配置上限 17，Redis 故障时启用限流的请求安全失败。
- 实现事务 Outbox→Kafka→独立 Worker 的异步发奖链路，以事件 ID 和中奖 ID 双唯一约束实现持久化幂等；Worker 使用 MySQL 租约、过期接管和令牌 fencing，瞬时失败有界退避，永久失败/耗尽转人工补偿审核队列。
- 为受保护的抽奖 HTTP 链路建立本机并发基准和依赖故障演练：限流开/关各 3 轮、每轮 40 请求/10 并发；校验 HTTP 结果、库存、Outbox、Worker 账本。分别注入 MySQL、Redis、Kafka 中断，验证失败隔离及恢复；仅描述本机试验，不外推生产吞吐或 Redis 性能优势。
- 构建轻量 Java Agent Harness，提供有界模型/工具循环、服务端角色授权、参数校验、只读 Campaign 查询、进程内 owner-scoped 多轮会话和上下文预算；Fake Model 与固定评测可离线复现。

## 可复现证据

- 全后端：在 `backend` 目录执行 `$env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -q package`。2026-09-24 Surefire 汇总 42 套件、283 项，0 失败、0 错误、0 跳过，服务 JAR 均生成。
- 抽奖→发奖：仓库根目录执行 `scripts/smoke-draw-worker-cross-process.ps1 -JavaHome 'D:\java\jdk-17'`。使用隔离 MySQL/Kafka 和 Campaign/Worker 两个 JVM，验证 Outbox `SENT`、Worker `SUCCEEDED`、账本恰一行。
- 失败恢复：同脚本增加 `-InjectTransientFulfillmentFailure`。2026-09-25 实际通过：单次中奖库存扣减正确，首次暂时失败后履约共尝试 2 次，Outbox `SENT`、Worker `SUCCEEDED`，人工审核任务 0、重复账本 0；仅本地模拟执行器，不连接真实供应商。
- 本机基准与故障：参数、三轮配对中位数/范围、逐请求原始 JSON 和恢复证据见 [`docs/baseline/README.md`](../baseline/README.md)。正式小样本中 Redis 开关两组都有运行间波动，不声称其带来吞吐提升。
- AI→Campaign：`scripts/smoke-ai-campaign-cross-process.ps1`，验证真实 Campaign JWT 与只读活动查询；模型明确是 Fake Model。
- 本地模拟发奖不会交付真实权益；没有真实模型、公网部署或生产集群证据。简历面试中应区分小规模本机基准、并发正确性测试和生产容量结论。

## 面试讲解顺序

1. 先画抽奖事务：认证 Actor → 幂等占位 → 活动/次数检查 → 随机选择 → 条件扣库存 → 结果与 Outbox 同事务提交。
2. 再解释 Outbox 为什么避免 MySQL/Kafka 双写不一致，以及消费者至少一次投递下如何用唯一键、租约和 fencing 防重与恢复。
3. 最后讲 Harness 的信任边界：模型只选择固定工具；身份由 HTTP 宿主验证并注入；工具白名单和资源权限在服务端执行，模型不接触写操作。

不要把本摘要里的测试规模描述成线上 QPS，也不要声称 Worker 已接入真实供应商或 Agent 已使用真实大模型。
