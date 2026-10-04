# PrizeFlux

PrizeFlux 是一个面向学习和求职展示的智能营销与高并发活动平台。项目围绕活动配置、权重抽奖、库存扣减、异步发奖、运营分析和 AI 运营助手，从空工程独立设计并逐步实现。

> 当前交付口径（2026-10-04）：PrizeFlux 是**一个本地演示的简历项目 v1**，Agent 是其中的只读运营辅助亮点，不是独立的第二个项目。本次验证记录与限制见[交付候选记录](docs/architecture/release-candidate-scope.md)。现有代码覆盖注册/身份、创作者提审/运营审核、即时与排队抽奖、MySQL 权威库存和幂等、Redis 入口保护、Outbox/Kafka 与模拟发奖，以及 Vue 3 与 Python Agent Harness。已有隔离环境的并发、Kafka 故障恢复、rebalance 和本地容器运行证据，**不代表公网生产容量**。本地知识索引已构建并做过权限/故障保持验证，但网站检索默认关闭，带鉴权的真实 RAG 页面路径与回答质量尚未验收；Kimi 兼容适配器有有限 smoke，默认仍用 Fake Model。真实奖品履约、公开部署、多 Broker 高可用和云端 CI 均未交付。进一步的技术背景见[交付计划](docs/architecture/product-delivery-plan.md)和[流量基线](docs/baseline/README.md)。

## 为什么做这个项目

普通 CRUD 项目很难体现事务、并发、一致性、消息可靠性和故障处理。本项目用一条营销链路串联这些主题，并以架构说明、自动化测试和可复现实验提供工程证据。

## 聚焦后的建设范围

项目按[聚焦版能力地图](CAPABILITY-MAP-FOCUSED.md)建设：保留高并发抽奖、MySQL 权威状态和 Kafka 可靠事件；Agent 部分重点自建 Harness，而不是只做聊天框。演示前端含用户参与、个人活动草稿、运营审核工作台及只读 Creator Agent 校验。任何 `USER` 都能参加抽奖并创建自有活动，提交后必须经 `OPERATOR` 审核才公开。真实供应商履约、真实模型调用与公网/云部署仍未交付，不作为已完成内容。

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
5. [Python Agent 迁移基线](docs/architecture/java-agent-baseline.md)：查看旧 Java Agent 的 API/权限合同与未接线模型原型。
6. [Python Agent Foundation](agent-service/README.md)：查看 Python 服务当前边界、依赖和本机启动方式。
7. [Agent 产品架构与实施计划](docs/architecture/product-agent-blueprint.md)：查看目标设计与分阶段验收。
8. [简历项目摘要](docs/interview/resume-project-summary.md)：按证据边界查看项目描述。
9. [来源与 AI 辅助说明](NOTICE.md)：了解项目边界和贡献说明。
10. [Vue 3 前端运行说明](web/README.md)：启动演示站点、代理 Campaign、Java AI 和 Python Agent API 并查看角色页面。
11. [本地全栈容器运行说明](deploy/container-local.md)：单独启动隔离 Compose 栈；仅 Nginx 映射到 loopback，保留独立 MySQL 卷。

## Agent Harness 当前运行状态

无需模型密钥或数据库即可运行的 Java Harness 演示和固定评测，见[运行说明](docs/learning/harness-demo.md)。所有演示活动数据都明确标为模拟数据，不代表真实跨服务联调。Java AI（8082）目前保留为 Creator 草案校验及旧会话迁移基线；运营活动助手改由 Python Agent（8083）提供。

Java AI 宿主默认监听 8082，Campaign 默认地址为 `http://localhost:8081`。`POST /api/ai/creator/validate` 供 USER 对尚未保存的活动草案做只读检查；Fake Model 返回模拟建议，不创建草稿、不提交审核、不发布、不抽奖。旧 Java OPERATOR 会话接口仍在，但当前产品助手页面已不再调用它。Python Agent 默认监听 8083，运营页面用它查询持久会话/运行状态；Fake 是默认模型，健康接口只显示 provider 类型。Kimi 兼容 adapter 曾做少量独立 smoke，不等于产品页面模型链路或回答质量验收。Python 环境和会话数据库说明见 [agent-service/README.md](agent-service/README.md)。

简历项目描述、面试讲解顺序和可复现证据见[项目摘要](docs/interview/resume-project-summary.md)。其中并发正确性数据不代表线上吞吐量，Worker 履约仍为模拟实现。

## Python Agent Harness（本地集成进行中）

Python 服务可在 loopback 的 8083 运行，提供受 Campaign 身份委托的只读工具、受限 Harness、独立 MySQL 会话/运行表和运营助手 API。Vue 运营助手已接入 Python API；浏览器仅在当前 tab 保存最近会话和运行 ID，历史以服务端 owner-scoped 数据为准。P9 索引构建、P10 受权检索、P11a Harness evidence 与 P12 离线 BM25 评测/Agent CI 均已有代码和本机自动化证据；本地真实向量索引已构建并验证重启及角色隔离，但网站检索默认关闭，带鉴权 HTTP Run/浏览器与回答质量仍待验。Campaign/Python/Vue 多进程 smoke 与 token 到期策略有隔离环境证据；远程 CI 对新增工作树未验证。P14-E 正式本地容器镜像与运行栈已有运行证据，今晚当前版本仍需复验。实现、配置与限制见 [agent-service/README.md](agent-service/README.md)。

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

# 注入一次本地模拟的暂时发奖失败；应经持久化重试后成功，且没有人工审核任务。
.\scripts\smoke-draw-worker-cross-process.ps1 -InjectTransientFulfillmentFailure

# 停掉临时 Kafka 验证排队请求的 MySQL/Outbox 持久化、Broker 恢复与 consumer 唤醒。
.\scripts\smoke-draw-worker-cross-process.ps1 -InjectDrawQueueKafkaOutage `
  -EvidencePath 'docs/baseline/draw-queue-kafka-recovery-final-2026-09-27.json'
```

完整链路脚本要求 Docker Engine 可用；它动态分配隔离的 loopback 端口，不连接或停止已有 Compose 服务，且只清理自身随机命名的临时资源。AI→Campaign 的只读跨进程演示见 [AI 服务运行手册](docs/runbooks/ai-service.md)。

## 本地环境

当前规划需要：

- JDK 17
- Python 3.12–3.14 与 uv（Agent 服务）
- Node.js/npm（Vue 前端）
- Maven 3.9+
- Git
- Docker Engine 与 Docker Compose（测试和集成演示需要）

## 自动化验证与秘密边界

GitHub Actions 会在推送和 Pull Request 时执行 Maven 模块校验、后端测试与秘密基线扫描。请以[远程 Actions 记录](https://github.com/yezang-ccc/prizeflux-platform/actions)中对应提交的结果为准；本机验证与远程 CI 分开记录。CI 通过不等同于公网部署、生产压测或真实供应商验证。

本地运行时配置应复制 `deploy/.env.example` 为被 Git 忽略的 `deploy/.env`；真实密码、Token、私钥和生产配置均不得提交、写入工作流或打入镜像。

D3/D4 开发用 MySQL 配置在 `deploy/compose.foundation.yml`。需要手工启动本地依赖时，复制 `deploy/.env.example` 为 Git 忽略的 `deploy/.env`，替换示例密码后执行 `docker compose --env-file deploy/.env -f deploy/compose.foundation.yml up -d`。启动 Campaign 的终端也需单独设置相同的 `PFX_DB_PASSWORD`，并启用 `local-full` Profile；Compose 的 `--env-file` 不会自动把变量传给另一个终端。独立进程抽奖、事件和发奖验证见 `scripts/smoke-draw-worker-cross-process.ps1`；该脚本使用自己的随机命名临时容器和隔离端口，不连接或停止这份 Compose 服务。

高峰队列使用独立 `draw-executor` 进程：先以 `local-full` 启动 Campaign API，再在另一个终端设置相同的数据库密码、JWT Secret 与 `KAFKA_BOOTSTRAP_SERVERS`，执行 `cd backend; .\mvnw.cmd -pl prizeflux-campaign spring-boot:run '-Dspring-boot.run.profiles=local-full,draw-executor'`。执行器不开放 HTTP 端口，与 API 共用队列消费者组；可运行多个实例分摊 Kafka 分区。本机默认允许应用创建 12 分区队列 topic；生产环境应预建满足副本/保留策略的队列和 DLT topic，并设 `PFX_KAFKA_MANAGE_TOPICS=false`，避免服务启动时变更现有 topic。MySQL 扫描器会恢复丢失通知和过期租约；Redis 只做准入限流，不参与开奖事实。

抽奖与发奖已具备受保护的本机演示链路；加 `-InjectTransientFulfillmentFailure` 可展示 Worker 失败后的持久化重试恢复。私有 GitHub `main` 已包含该演示；提交 `f43e878` 的 Maven 校验、后端测试和秘密扫描通过，不代表生产部署完成。

## 学习与贡献方式

欢迎通过 Issue 提出问题。公开贡献开始前会补充贡献指南。核心业务和安全边界应有自动化测试与可复现的验证步骤。

## 来源和许可证

项目的营销活动业务场景受到 Java 后端训练课程中的抽奖案例启发，但新仓库不复制课程源码、SQL、配置、注释或 Git 历史。详细说明见 [NOTICE.md](NOTICE.md)。

本项目的独立原创代码暂按 [Apache License 2.0](LICENSE) 开源；正式发布前会再次确认授权范围。
