# D4-01 抽奖持久化结构规格

状态：依据已确认的 64 项范围与 [全量架构基线](docs/architecture/build-blueprint.md)进入实施。当前只建立 Campaign 域的 V2 数据结构和不变量；事务服务、库存 Mapper、HTTP、Worker 表在后续 D4/D7 实现。

## 目标与边界

- V1 `campaign` 表不修改；Flyway V2 新增配置版本、奖品配置、实时库存、业务请求、结果、业务日次数、Outbox。
- MySQL 是库存、业务参与次数、请求结果和待发布事件的最终事实。Redis 不在此迁移中承担正确性。
- 奖品配置概率使用 `DECIMAL(7,6)`，与百万票号一致；负数、大于 1、负库存、非法状态由 CHECK 约束拒绝。
- `(operation,user_id,request_key)` 唯一，指纹用 SHA-256 32 字节保存；同键不同指纹由应用层返回冲突。`draw_result.request_id` 唯一，确保一个请求仅有一个结果。
- 用户身份表在 D8 建立，`user_id` 暂存服务端 Actor ID，不允许从外部 DTO 直接信任；D8 再补相应查询和约束。
- 发布与冻结配置、扣库存、扣业务次数、写结果和 Outbox 的事务语义见架构基线。本迁移不声称已有这些服务。
- Worker 的去重和发奖账本位于 Worker 独立迁移，不写入 Campaign V2。

## 文件与批次验证

迁移：`backend/prizeflux-campaign/src/main/resources/db/migration/V2__create_draw_tables.sql`。架构说明：`docs/decisions/ADR-002-database-invariants.md`。本批次结束后用 MySQL 8.4 Testcontainers 验证迁移、唯一键、CHECK、库存条件更新和事务回滚；当前不逐表跑测试。
