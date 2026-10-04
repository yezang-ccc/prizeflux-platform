# 受控知识语料维护说明

本文件供项目维护者管理语料，**不在 `manifest.json` 白名单内，不会被索引**。索引器只读取 manifest 明确列出的 UTF-8 Markdown/TXT 文件；不要改成递归扫描或从用户活动、聊天记录和网页抓取内容。

## 当前文档、来源与权限

| 稳定文档 ID | 索引路径 | 来源依据 | Scope | 版本依据 |
| --- | --- | --- | --- | --- |
| `campaign-help-v1` | `public/campaign-help.md` | 产品页面及服务端注册、活动、抽奖接口；逐项锚点见下 | `PUBLIC` | 文档 ID 表示内容契约主版本；规范化内容 SHA-256 形成 document version |
| `operator-guide-v1` | `operator/operations-guide.md` | 运营审核、活动管理、队列及监控实现；逐项锚点见下 | `OPERATOR` | 文档 ID 表示内容契约主版本；规范化内容 SHA-256 形成 document version |

权限只由 `manifest.json` 的 `scope` 字段决定，不能从文档正文、请求或向量库 payload 推断。`path`/数据库中的 `source_path` 是仓库内来源定位，不是可供用户读取任意文件的路径。当前语料是项目维护的产品说明，不声称来自外部标准或法律意见。

## 维护锚点

- 注册规则：`web/src/views/RegisterView.vue`、`backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/application/UserRegistrationService.java`、`RegistrationRateLimiter` 实现与测试。
- 活动参与、抽奖状态与请求重试：`web/src/views/CampaignDetailView.vue`、`backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/application/DrawQueueRepository.java`、`DrawQueueApplicationService.java`。
- 创建者草稿与审核流程：`web/src/views/CreatorCampaignEditView.vue`、`web/src/views/OperatorReviewView.vue`、Java `CampaignReviewService` 和相关集成测试。
- 运营活动及监控边界：`web/src/views/OperatorCampaignsView.vue`、`docs/architecture/observability.md`、`docs/architecture/build-blueprint.md`。

## 更新规则

1. 先以实现、规格和相关集成测试确认事实，再同时更新对应公开/运营文档；禁止把计划中、未运行或仅 Fake 的能力写成现状。
2. 小幅措辞修正保留稳定 document ID；规则语义发生不兼容变化时创建新的 `documentId` 主版本，不覆盖旧索引产物。每次内容变化都会产生新的内容 hash 和索引 fingerprint。
3. `scope` 只能在受审 manifest 中修改。公开文档不得混入运营步骤；运营文档不得包含用户私有活动或个人参与数据。
4. 更新后运行索引单测/CLI dry-run；真实 Embedding、Qdrant 构建、真实召回与回答质量必须分别记录，不能用 HashEmbedding 或静态语料检查替代。
5. 本次维护复核日期：2026-09-30。改变业务事实时再更新此日期，并记录依据文件/测试，不通过日期字段伪造外部来源或发布版本。
