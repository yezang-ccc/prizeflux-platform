# PrizeFlux Vue 3 演示前端能力地图

状态：首版完整演示前端、自助注册、“USER 创建自有活动草稿、OPERATOR 审核后发布”及创建者只读 Agent 校验已实现。2026-09-28 UI 按 Element Plus/Pure Admin 模式改造中：认证、活动目录和活动详情已收整；历史、创作者、运营、Agent 页仍待改造。自动化与真人浏览器验收边界见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

## 能力与依赖

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `campaign-read-api` | 运营员查看活动列表/草稿配置；参与用户浏览已发布活动与奖品；按角色限制可见范围 | 现有 Campaign、身份认证 |
| `vue3-product-web` | Vue 3 单页应用、登录与角色导航；用户活动发现/抽奖/历史；运营活动创建/奖品配置/发布/关闭 | `campaign-read-api`、现有 Campaign 写 API |
| `vue3-agent-console` | 运营员创建/延续/读取/删除 AI 会话并提交问题；仅展示只读活动辅助 | `vue3-product-web`、现有 Harness HTTP API |
| `campaign-ownership` | USER 创建与维护自己的草稿，活动 owner 从认证上下文取得 | Campaign、身份认证 |
| `campaign-review` | 运营审阅草稿、写理由并原子地拒绝或发布 | `campaign-ownership`、配置冻结 |
| `creator-console` | 我的活动/新建草稿与全站待审核入口 | owner/review API |
| `creator-agent-copilot` | AI Harness 为结构化草稿调用确定性校验工具并生成建议 | `creator-console`、Harness |

构建顺序：既有 `campaign-read-api` → `campaign-ownership` → `campaign-review` → `creator-console` → `creator-agent-copilot` → Campaign/MySQL/Redis/Kafka/AI 本机端到端演示。

## 共享产品边界

- 普通 `USER` 可自助注册、参与已发布活动并创建自己的草稿，不获得运营全局权限；`OPERATOR` 不开放自助注册，仍须受控预置。owner 必须从服务端 Actor 读取，草稿/配置查询按 owner 过滤。匿名注册仍按服务端 remote IP 走 Redis 固定窗口（5 次/10 分钟）；Redis 故障时拒绝注册。
- SPA 只调用公开 HTTP API，不连接数据库、Redis 或 Kafka。浏览器不拥有业务授权；每个接口仍由后端校验 JWT 与角色。
- 当前运营 AI 页面显著标注 Fake Model、仅只读查询。新增创建者 Agent 只调用只读配置校验工具并返回建议；保存必须由用户显式调用 Campaign API，Agent 不可审核/发布/抽奖/更改库存。
- 首版只展示抽奖结果、活动与时间；不把 Worker 自有账本状态误作 Campaign 已确认的最终发奖状态。真实模型、供应商履约、公网部署不属于本图。注册有基础 IP 限流，但无邮箱验证、验证码或注册滥用监控，仅用于本机演示；公网开放须补独立安全方案。
- 参与者端不负责提供活动 ID。新增创建者 API 使用服务端生成 ID；旧 OPERATOR API 暂保留 ID 合同以兼容既有演示写入，公开 DTO 仍需避免 Java long 超 JS 安全范围的静默精度损失。

## 规格与验收入口

- [`SPEC-campaign-read-api.md`](SPEC-campaign-read-api.md)：角色安全的列表与配置读取接口。
- [`SPEC-vue3-product-web.md`](SPEC-vue3-product-web.md)：Vue 3 基础、认证、用户与运营页面。
- [`SPEC-vue3-agent-console.md`](SPEC-vue3-agent-console.md)：AI Harness 会话体验。
- [`SPEC-campaign-ownership.md`](SPEC-campaign-ownership.md)、[`SPEC-campaign-review.md`](SPEC-campaign-review.md)、[`SPEC-creator-console.md`](SPEC-creator-console.md)、[`SPEC-creator-agent-copilot.md`](SPEC-creator-agent-copilot.md)：创建归属、审核、工作区与 Creator Agent。
