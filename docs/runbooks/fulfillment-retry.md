# Worker 发奖重试与人工审核

## 当前行为

Worker 先把 Kafka 事件和 `PENDING` 账本写入自己的 MySQL，再由持久化轮询器领取到期记录。领取使用 `FOR UPDATE SKIP LOCKED` 与 60 秒租约；结果写回必须携带当前租约令牌，过期 Worker 不能覆盖新 Worker 的结果。

本地演示执行器只记录“模拟发奖成功”，不会调用供应商或交付真实权益。执行器失败时，瞬时故障按 1 秒、2 秒退避重试，最多执行 3 次；永久故障立即转 `MANUAL_REVIEW`。瞬时故障耗尽次数也转 `MANUAL_REVIEW`。转人工审核和插入唯一审核任务在同一 MySQL 事务中完成。重复 Kafka 事件仍由 `event_id` 与 `draw_id` 唯一键挡住。

## 查询状态

在 Worker 自己的 schema（默认 `prizeflux_worker`）执行：

```sql
SELECT draw_id, status, attempt_count, next_attempt_at, last_error_code, updated_at
FROM fulfillment
ORDER BY id DESC
LIMIT 50;

SELECT draw_id, reason_code, state, created_at, resolved_at
FROM fulfillment_compensation_task
WHERE state = 'OPEN'
ORDER BY id;
```

`MANUAL_REVIEW` 是等待受控人工判断的队列，不代表系统自动退款或真实外部补偿已完成。当前版本不提供 Worker HTTP 管理入口；不要直接把该 schema 暴露到公网。人工重放 API、Campaign 发奖状态回流以及真实供应商幂等键须单独完成安全设计。

## 配置与验证

Worker 默认不启用执行器或重试轮询；本机跨进程冒烟显式使用 `--spring.profiles.active=local-full`，开启 `simulated` 执行器及持久轮询。部署环境必须提供经过审查、支持幂等键和超时的真实执行器，再启用 `prizeflux.worker.retry.enabled` 与 `scheduler-enabled`。跨进程本地冒烟会验证抽奖 Outbox 经 Kafka 到 Worker 后最终变为 `SUCCEEDED`。迁移 `V2__add_fulfillment_retry_and_review.sql` 为已有 Worker schema 增列并新建补偿任务表。

要在完整独立进程链路中演示“暂时发奖失败 → MySQL 持久化等待 → Worker 重试 → 成功”，先按仓库根目录 README 的命令构建 JAR，然后运行：

```powershell
.\scripts\smoke-draw-worker-cross-process.ps1 -InjectTransientFulfillmentFailure
```

此参数只给该次隔离 Worker JVM 的模拟适配器注入每个中奖 draw ID 一次暂时失败；默认运行不注入故障。脚本等待 Outbox=`SENT`、Worker=`SUCCEEDED`，并断言尝试数符合场景、没有重复发奖账本和人工审核任务。它不模拟真实供应商、退款或人工审核动作，也不能证明公网运行能力。
