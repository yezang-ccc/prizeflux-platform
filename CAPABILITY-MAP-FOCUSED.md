# PrizeFlux 聚焦版能力地图

状态：2026-09-24 经用户确认，以 Java 17 开发 Agent Harness。它是新的聚焦建设范围；原 `CAPABILITY-MAP.md` 和 `tasks/todo.md` 保留为历史 64 项清单，不能把延期任务误记为完成。用户希望缩小总体范围，但保留高并发、分布式与消息队列，并将精简版后续建设约一半投入 Agent Harness。

## 模块与依赖

| ID | 可独立验收的职责 | 依赖 |
|---|---|---|
| `campaign-core` | 保留现有 Java 活动、认证、抽奖、MySQL 库存/次数/幂等/结果链路；补齐真实 HTTP 闭环 | 现有后端 |
| `traffic-proof` | 聚焦限流与并发验证、故障实验和原始指标；Redis 只用于抗滥用/性能辅助，不充当库存事实 | `campaign-core` |
| `event-fulfillment` | Outbox → Kafka → Worker 持久化幂等发奖、失败重试/人工处理、独立进程联调 | `campaign-core` |
| `harness-runtime` | 自建最小 Agent 循环：模型适配、消息状态、受控工具调用、停止条件、超时和预算；Fake Model 离线可测 | 无业务依赖 |
| `harness-safety` | 类型化只读工具注册、服务端授权、参数验证、调用轨迹；通过受授权的 Campaign 查询 HTTP 接入业务 | `harness-runtime`、`campaign-core` |
| `harness-evidence` | 上下文压缩/会话、确定性回放与评测、可操作 CLI 演示；真实模型是后续可选适配 | `harness-runtime`、`harness-safety` |

## 构建顺序与取舍

先收口并验证现有 `campaign-core` 与 `event-fulfillment`，同期落实窄范围的 `traffic-proof`；再实现 `harness-runtime`、`harness-safety`、`harness-evidence`。每批做定向检查，阶段末集中回归。简历展示应同时提供真实抽奖链路证据和可复现的 Agent 轨迹/评测，不能用架构图代替运行结果。

本提案暂不继续扩展秒杀、短链、完整 Vue 后台、通用网关、云端部署、多 Agent、任意 Shell 工具或插件市场。原 64 项保留为历史总清单；确认新范围后另建聚焦任务清单，逐项标明保留、延期和放弃，不把未做项误记为完成。

## 参考与不可逾越的边界

- `SWE-agent/mini-swe-agent`：学习最小模型→动作→结果循环；不照搬 Bash 执行工具。
- `HKUDS/OpenHarness`：参考工具、权限、上下文分层；不照搬大而全的实现。
- `langchain-ai/deepagents`：参考状态与扩展设计；不以框架封装隐藏首版控制循环。
- Harness 使用本仓库已有的 Java 17 `backend/prizeflux-ai` Maven 模块；其核心循环保持框架无关，Campaign/Worker 保持现有架构。
- AI 只能通过受授权、只读的业务接口查询，不参与随机开奖、库存扣减或运营写入。凭据、真实模型费用和公开部署都需另行决定；首版只用 Fake Model，不要求密钥。
- 参考项目用于学习设计，不复制源码；实际采用第三方依赖前核对许可证、维护状态和安全边界。
