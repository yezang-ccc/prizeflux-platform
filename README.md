# PrizeFlux

PrizeFlux 是一个面向学习和求职展示的智能营销与高并发活动平台。项目围绕活动配置、权重抽奖、库存扣减、异步发奖、运营分析和 AI 运营助手，从空工程独立设计并逐步实现。

> 当前状态（2026-09-24）：活动/抽奖与 JWT 身份、MySQL 库存/幂等、Outbox→Kafka→独立 Worker、持久化发奖重试与人工补偿任务已具备；发奖执行器仍是明确标注的本地模拟实现。Java Agent Harness 已有最小循环、受控只读工具、Campaign 查询适配、离线演示和独立 Spring Boot HTTP 宿主。Redis 用户级抽奖入口限流已实现；本机 40 请求/10 并发对照和 MySQL/Redis/Kafka 故障恢复证据已归档；最近一次全后端回归为 41 套件、281 项通过，0 失败/错误/跳过。吞吐仅为本机小样本，不代表生产容量或 Redis 提速。真实供应商、真实模型、远程 CI 和公网部署尚未完成，不能宣称已上线。实验条件和结果见[流量与故障基线](docs/baseline/README.md)。

## 为什么做这个项目

普通 CRUD 项目很难体现事务、并发、一致性、消息可靠性和故障处理。本项目用一条营销链路串联这些主题，并以架构说明、自动化测试和可复现实验提供工程证据。

## 聚焦后的建设范围

项目按[聚焦版能力地图](CAPABILITY-MAP-FOCUSED.md)建设：保留高并发抽奖、MySQL 权威状态和 Kafka 可靠事件；Agent 部分重点自建 Harness，而不是只做聊天框。未列入当前范围的前端、云部署和真实模型等能力不作为已完成内容。

## 架构原则

1. AI 不参与中奖结果、库存扣减等确定性核心链路。
2. 数据库约束承担库存和幂等的最终正确性防线。
3. 业务层依赖接口，不直接依赖具体框架适配器。
4. 任何性能数字都必须附带环境、脚本和原始结果。
5. 不提交密码、Token、服务器地址或其他秘密。

## 技术文档

1. [聚焦版能力地图](CAPABILITY-MAP-FOCUSED.md)：查看当前实际建设范围。
2. [全量构建架构基线](docs/architecture/build-blueprint.md)：查看跨模块契约和交付顺序。
3. [流量与故障基线](docs/baseline/README.md)：查看本机实验条件、结果和限制。
4. [AI 服务运行手册](docs/runbooks/ai-service.md)：复现独立 AI→Campaign 只读链路。
5. [简历项目摘要](docs/interview/resume-project-summary.md)：按证据边界查看项目描述。
6. [来源与 AI 辅助说明](NOTICE.md)：了解项目边界和贡献说明。

## Agent Harness 离线演示

无需模型密钥或数据库即可运行的 Java Harness 演示和固定评测，见[运行说明](docs/learning/harness-demo.md)。所有演示活动数据都明确标为模拟数据，不代表真实跨服务联调。

独立 AI HTTP 宿主入口为 `GET /api/health`、`POST /api/ai/campaign-summary` 和 OPERATOR 多轮会话 `/api/ai/sessions`，默认监听 8082，Campaign 默认地址为 `http://localhost:8081`。入口会把调用者的 Bearer token 转交 Campaign 验证身份，只允许 OPERATOR；Fake Model 返回内容带 `simulated=true`。会话目前仅驻留 AI 进程内存，重启/切实例后不保留；真实跨进程验证和边界见 [AI 服务运行手册](docs/runbooks/ai-service.md) 与 [Harness 会话学习说明](docs/learning/harness-sessions.md)。

简历项目描述、面试讲解顺序和可复现证据见[项目摘要](docs/interview/resume-project-summary.md)。其中并发正确性数据不代表线上吞吐量，Worker 履约仍为模拟实现。

本机抽奖 HTTP 基准与 Redis/MySQL/Kafka 故障演练的参数、汇总和原始 JSON 见[流量与故障基线](docs/baseline/README.md)。

## 本机验证与完整链路演示

```powershell
# 在仓库根目录运行；若 JAVA_HOME 尚未设置，请改为本机 JDK 17 安装目录。
$env:JAVA_HOME = 'C:\path\to\jdk-17'
Set-Location .\backend
.\mvnw.cmd -q test
.\mvnw.cmd -q -DskipTests package
Set-Location ..

# Campaign → Kafka → 独立 Worker；脚本使用隔离临时容器和端口。
.\scripts\smoke-draw-worker-cross-process.ps1
```

完整链路脚本要求 Docker Engine 可用且演示端口未占用；它不连接或停止已有 Compose 服务。AI→Campaign 的只读跨进程演示见 [AI 服务运行手册](docs/runbooks/ai-service.md)。

## 本地环境

当前规划需要：

- JDK 17
- Maven 3.9+
- Git
- Docker Engine 与 Docker Compose（测试和集成演示需要）

## 自动化验证与秘密边界

仓库已准备 GitHub Actions 工作流，用于未来的后端构建、测试和秘密基线检查；截至当前提交，尚无 GitHub Actions 的远程运行记录。项目只会如实声明本机验证状态，直到对应提交获得远程 CI 证据。

本地运行时配置应复制 `deploy/.env.example` 为被 Git 忽略的 `deploy/.env`；真实密码、Token、私钥和生产配置均不得提交、写入工作流或打入镜像。

D3/D4 开发用 MySQL 配置在 `deploy/compose.foundation.yml`。需要手工启动本地依赖时，复制 `deploy/.env.example` 为 Git 忽略的 `deploy/.env`，替换示例密码后执行 `docker compose --env-file deploy/.env -f deploy/compose.foundation.yml up -d`。启动 Campaign 的终端也需单独设置相同的 `PFX_DB_PASSWORD`，并启用 `local-full` Profile；Compose 的 `--env-file` 不会自动把变量传给另一个终端。独立进程抽奖、事件和发奖验证见 `scripts/smoke-draw-worker-cross-process.ps1`；该脚本使用自己的随机命名临时容器和隔离端口，不连接或停止这份 Compose 服务。

抽奖业务已具备受保护的本机演示链路。当前交付工作转入发布审查和剩余聚焦任务，不代表生产部署完成。

## 学习与贡献方式

欢迎通过 Issue 提出问题。公开贡献开始前会补充贡献指南。核心业务和安全边界应有自动化测试与可复现的验证步骤。

## 来源和许可证

项目的营销活动业务场景受到 Java 后端训练课程中的抽奖案例启发，但新仓库不复制课程源码、SQL、配置、注释或 Git 历史。详细说明见 [NOTICE.md](NOTICE.md)。

本项目的独立原创代码暂按 [Apache License 2.0](LICENSE) 开源；正式发布前会再次确认授权范围。
