# PrizeFlux 本地交付候选：2026-10-04

本文件是今晚的时间盒，不替代聚焦能力图或长期交付计划。目标是形成**可在本机复现、可演示、可安全提交到当前 private Git 仓库**的候选版本；不是公网生产上线，也不等于用户已经掌握面试知识。

用户确认：本次收口后将 PrizeFlux 作为**单个简历项目 v1**冻结，不再以长期清单持续扩功能；后续仅修复影响演示、安全、数据正确性的缺陷。用户转入项目原理和面试答辩准备，开发重心再移至独立的第二个 Java 项目。未完成的研究/生产化事项保持如实记录，不借“冻结”改写为已完成。

## 本次冻结范围

1. 用户端：注册/登录、浏览已发布活动、即时抽奖、排队抽奖及结果/历史查询。
2. 创作者与运营端：用户创建草稿并提审；运营账号审核与发布。权限与活动归属由服务端验证。
3. 可靠链路：MySQL 权威库存/次数/幂等与 Outbox，Kafka 队列/发奖模拟 Worker，Redis 入口保护。保留已有故障与并发证据，不在今晚追求新的性能纪录。
4. Agent：保留已实现的 Python Harness、鉴权只读工具、会话/运行记录及 Fake Model 演示。真实 Kimi、RAG 页面开关和回答质量**不作为本次交付门槛**；不得在简历或 README 中写成已上线。
5. Vue 3：以现有页面完成本机主链路，不再做新一轮视觉重设计。

## 今晚交付门槛（全部满足才称“可提交候选”）

- 后端、Agent、前端集中回归与生产构建通过；库存、请求幂等、权限、排队终态至少有自动化证据。失败必须区分代码、环境与未运行。
- 使用**现有产品卷**安全启动本地 Compose，检查迁移/健康；以 USER 与 OPERATOR 做主链路 HTTP/页面冒烟。不得 `down --volumes`、清空库或重置工作树。若容器环境不可用，交付状态降为“源码候选，运行未验收”。
- 修正 README/运行说明的过期完成声明，给出从干净机器启动的步骤、演示账号初始化方式和明确限制；不能提交真实凭据或本机私有数据。
- 对待提交文件做逐项审查、`git diff --check`、秘密/许可证检查；只暂存相关源码、配置模板、必要文档与可复现测试。先检查 staged diff，再提交。GitHub 保持 private；本次不擅自改公开性或声称云端部署。

## 时间盒与止损规则

- 17:00–18:00：冻结范围、改动清点、确认验证命令和当前容器状态。
- 18:00–20:30：集中跑 Java/Python/Vue 测试与构建；仅修阻断主链路或安全/数据正确性的故障。
- 20:30–22:00：本机产品栈和角色主链路冒烟，记录真实结果。
- 22:00–23:30：更新 README/交接、审查暂存文件和秘密，再形成提交；留 30 分钟处理环境与提交异常。

若 22:00 前关键回归或主链路仍失败，停止扩功能，优先修复；若到截止仍未通过，保留所有改动并交付精确阻碍清单，**不把失败版本标为完成**。

## 2026-10-04 本机验证记录（17:30 快照）

- Java：`$env:JAVA_HOME='D:\java\jdk-17'; backend\mvnw.cmd -q test`，完整复跑 **56 suites / 304 tests / 0 failures / 0 errors / 0 skipped**；`-DskipTests package` 成功。首轮 Worker 6 个集成用例因测试漏设独立 Flyway URL 指向默认 3307 而失败；已把 Flyway URL/账号与 Testcontainers MySQL 对齐，定向 6 项和完整复跑均通过。
- Agent：`uv run --locked pytest -m 'not mysql' -q` 为 **163 passed / 17 deselected**；启用隔离 MySQL 后 `pytest -m mysql -q` 为 **17 passed / 163 deselected**；Ruff 与 mypy（55 个源文件）通过。
- Web：`npm run test -- --run` 为 **14 files / 41 tests passed**；`npm run build` 成功。
- Docker：完整 `compose build --pull=false` 在 Worker 镜像内 Maven Central TLS 握手失败；不是源码编译失败。宿主当前源码 JAR 已打包，并用 `backend/Dockerfile.local-artifact` 构建 Campaign/Worker/legacy AI 运行镜像；draw-executor 与 bootstrap 使用同一 Campaign JAR。Proxy 镜像从当前 Web 源码构建成功；Agent/迁移镜像沿用 10 月 2 日已构建版本（Agent 源码此后未改）。`compose up --no-build --detach` 保留原产品卷成功；9 个长期容器运行，具健康检查的服务健康，迁移 job 正常退出。`/login`、`/agent-api/health/ready` 为 200，未登录 `/api/auth/me` 为 401。
- 同源 HTTP 最小冒烟：临时随机 USER 注册/登录、`/api/auth/me`、活动目录、我的活动列表成功；该 USER 访问运营审核列表为 403；Agent 以临时随机 USER 创建 session、提交 Fake run 并查询到 `COMPLETED`。没有调用付费模型。此轮未使用现有 OPERATOR 凭据做真实网页审核/发布，也未新开抽奖或排队请求；这两段仍依靠本轮 Java 集成测试与既有历史运行证据，不能称本轮浏览器全流程完成。
- 离线知识评测 `uv run --locked python -m prizeflux_agent.knowledge_eval` 退出 0：5 个 BM25-only 样例、`passed=true`、ACL violation=0；**未调用生成模型，也不是网站 RAG 效果评分**。
- 暂存前及暂存后均检查忽略边界，`.env.product`、本地权重、构建产物、个人学习记录未入 Git。`git diff --cached --check` 通过；本地 `scripts/check-secrets.ps1` 对 704 个候选文件通过。秘密规则原先将 Python 变量/示例占位符误判为秘密，已收紧并用 4 个合成正反例复核；最小扫描不替代专业审计。远程 CI 尚未运行。

## 收口状态（18:47 更新）

- 用户使用现有 OPERATOR 账号确认本地活动审核页可正常显示活动；本轮没有批准/驳回真实活动，运营写路径仍以 Java 集成测试和先前人工验收为依据。
- 本地整合提交 `3772976` 已推送到既有 **private** 仓库 `origin/main`（Git 回执 `1c6088f..3772976 main -> main`）；没有改变仓库可见性。该提交之后远程 CI 的结果尚未核对，不写成已通过。后续仅修演示、安全或数据正确性阻断问题，项目转入本人学习/面试准备。

## 延后，不写为本次已交付

- K3 带鉴权 HTTP/浏览器真实 RAG、引用支撑评测，Q 正式模型评测及付费调用。
- J4 精确“DB 提交后、Kafka offset 提交前”进程崩溃窗口；更高负载/多 Broker 容量结论。
- 公网部署、真实外部奖品履约、多租户、云备份/PITR、全站新视觉改版。
- 第二个 PowerJob 项目以及用户本人对项目的 L1–L4 学习验收。
