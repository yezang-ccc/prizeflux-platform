# PrizeFlux Python Agent 能力地图

2026-09-29 更新：[双项目竞争力增强](docs/architecture/competitiveness-enhancement-plan.md) 中 Creator A1–A5 代码、自动化验证及 Fake 隔离跨进程运行已完成；完整 `deploy/run-isolated-process-smoke.ps1` 退出码 0。真人浏览器/窄屏走查、真实索引/引用和保留集评测仍待实施；代码存在、自动化通过、真实模型效果与本人学习分别验收。

日期：2026-09-28。状态：P4～P8 服务底座、Harness、Campaign 受权只读工具、Agent MySQL RunStore/API、取消/SSE/恢复/留存与 Vue 助手已通过自动化验证。P9 索引构建链和 P10 hybrid retrieval/ACL/hash/revocation 内核通过 mock/MySQL 验收。P11a 已接入 Harness/Run API/Vue 来源清单：证据默认关闭、无证据且无业务工具时确定性拒答、完成前再验证 active version/撤回、run 内原子保存无原文的 citation metadata。P12 离线 BM25 评测集与 Agent CI 本机已通过；指标仅覆盖静态词法检索，不代表真实 Hybrid/LLM 质量。P14-C Campaign↔Python↔Vue/legacy AI 隔离进程 smoke、Agent owner/SSE/持久化和 JWT 过期拒绝现已通过。P13 Kimi 真实 adapter 多步工具协议已通过；因域内 tool name 带点号，供应商适配器新增下划线别名和反向映射，定向 9 tests 与双轮 provider smoke 通过。产品页面的 Campaign 权限工具执行与最终回答仍待真实活动 ID 浏览器复测；readiness 为 `openai-compatible / retrieval=false`，真实 Embedding/Qdrant/active index、真实 RAG 引用评测、Kafka 容量与发布验收也未完成。不能表示真实 RAG 已上线或完整 Agent 已公网可用。统一方案见 [产品架构](docs/architecture/product-agent-blueprint.md)，交付顺序与验收见 [实施计划](docs/architecture/product-delivery-plan.md)。

## 产品边界

一个产品、两个可独立演示的子项目：Java PrizeFlux 负责活动创建/审核、参与、开奖及可靠事件；Python PrizeFlux Copilot 负责活动草案检查、规则解释与有依据的运营建议。共用 Vue 3 产品入口，保留独立构建、测试和部署边界，源码放在同一个仓库。

## 能力与依赖

| 稳定 ID | 职责与独立验收结果 | 依赖 |
|---|---|---|
| `agent-python-foundation` | Python 包、FastAPI、配置、ModelPort、Fake/供应商适配、Mock HTTP 合同测试 | 无业务依赖 |
| `agent-python-runtime` | LangGraph 控制流、服务端工具策略、步数/时限/输出预算、结构化运行事件 | `agent-python-foundation` |
| `agent-python-campaign-tools` | 委托 Java 验证身份，按角色/归属调用固定只读 API；草案检查与受权摘要 | `agent-python-runtime`、现有 `campaign-core`/ownership/read API |
| `agent-python-sessions` | P7～P8 已实现 Agent 独立 MySQL schema/Alembic、owner-scoped session/run/history/events、请求幂等、单会话占有与执行 fencing；API、有限并发 worker、持久取消、SSE 续读、租约中断恢复、本人删除与默认 30 天清理；页面支持显式恢复本人仍为 ACCEPTED 的原 run | `agent-python-campaign-tools` |
| `agent-knowledge-rag` | P9 白名单语料、版本化 chunk、BM25 snapshot/Qdrant build adapter/原子索引指针；P10 hybrid retrieval + MySQL ACL/hash/revocation；P11a Harness 接入、拒答/前置保存复核、Run citation metadata 和 Vue 来源清单。默认关闭，真实 active index 尚未构建 | P9 依赖 Agent MySQL、EmbeddingPort/Qdrant；P10/P11a 依赖 Java 身份契约和 Harness |
| `agent-product-workflows` | 草案检查报告、规则问答、驳回解释；Vue 来源线索展示。真实证据质量/跨账号浏览器验收待做 | `agent-python-sessions`、`agent-knowledge-rag` |
| `agent-release-evidence` | 离线/真实模型分层评测、故障实验、脱敏运行记录、Compose 与迁移回滚验收 | 上述各模块 |

构建顺序：收口已有 `queued-draw`/Web 未验证工作 → foundation/runtime/Campaign tools（P4～P6 已完成）→ durable sessions/runs（P7～P8 服务 API/事件/SSE/取消/租约恢复/删除/留存和 Vue 助手已实现）→ P9 索引 build → P10 受权检索 → P11a Harness/Run API/Vue metadata 接入（代码验收完成）→ P12 离线评测与本机 Agent CI（已完成）→ P13 真实 provider/索引评测（需用户配置与授权）→ P14/P15 发布证据。Campaign↔Python↔Vue 真实进程 smoke 与 token 过期策略可先在不调用真实 provider 的情况下验收。执行仍在当前窗口串行推进，不新建子 Agent。

## 不变项与延期项

- 模型不能获得创建/审核/发布/开奖/库存写工具。用户在报告中选择建议后，仍由现有页面显式保存并由 Java 复验。
- Fake 默认；真实生成模型、Embedding 下载/外部 API 和费用在实施时按实际配置处理，未授权付费调用。
- 首版知识库只分公开帮助与运营手册；个人活动与参与记录保持实时受权查询，不进向量库。
- 首版不增加自主多 Agent、Shell/任意 Python 执行、浏览器代理、任意网页抓取、知识图谱、训练/微调、MCP 平台或通用工作流编辑器。
- Java Harness 作为迁移基线保留；Python 验收和前端切换成功前不删除。新计划替代继续扩展 Java 模型适配器的路线。
- 方案与架构审查前按 `AGENTS.md` 提醒用户选择高推理模型；用户已于本次确认切到 Astra，无需重复提醒。
