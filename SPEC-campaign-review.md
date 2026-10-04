# Spec: `campaign-review`

状态：用户已确认“创建者提交后由运营审核，通过后公开发布”，并要求实施。能力边界见 [`CAPABILITY-MAP-CREATOR-PLATFORM.md`](CAPABILITY-MAP-CREATOR-PLATFORM.md)。

## Objective and Workflow

阻止普通创建者自行发布其营销活动，同时使其能看到审核结果与修改理由。状态迁移：`DRAFT → PENDING_REVIEW → PUBLISHED`（通过）或 `REJECTED`（驳回）；被驳回活动经创建者修改后回到 DRAFT，可再次提交。运营审核记录仅追加，不覆盖历史。

## Contract

- `GET /api/ops/reviews/campaigns?before=&limit=`：OPERATOR 专用，返回待审活动摘要与 owner。
- `GET /api/ops/campaigns/{id}/configuration`：OPERATOR 读取待审活动配置；USER 不能读取其他 owner 的待审内容。
- `POST /api/ops/reviews/campaigns/{id}/approve`：OPERATOR 专用；只接受 PENDING_REVIEW。单事务校验配置、冻结库存/奖品版本、写审核记录、状态改为 PUBLISHED。
- `POST /api/ops/reviews/campaigns/{id}/reject`：OPERATOR 专用；理由必填，去除首尾空白后长度 3–500；单事务追加审核记录并改为 REJECTED。
- 重复/并发审阅使用条件状态更新，只有一个决策成功，其余得到稳定 409。USER 不得调用 review API；Agent 无 review 工具。
- V4 保存 owner/提交时刻和独立审核决定记录；V1–V3 保持只读兼容。旧 OPERATOR 活动维持现有直接发布路径，不伪造历史审核记录。

## Tech, Structure and Commands

沿用 Campaign application/domain/MyBatis/JDBC/Flyway；使用同一数据库事务完成决策记录、campaign 状态与配置冻结。发布调用既有验证器，不复制概率规则。

```powershell
Set-Location -LiteralPath '.\backend'
$env:JAVA_HOME = 'D:\java\jdk-17'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=CampaignApiIntegrationTest,CampaignTest,CampaignRepositoryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## Code Style

应用服务先用 owner/role/context 约束操作，再以状态条件更新实施竞争安全迁移；Controller 只转换 DTO，不自行判断审核权限或配置有效性。

## Testing Strategy

真实 MySQL 验证提交前缺配置拒绝、审批原子冻结/发布、驳回理由可见、驳回后编辑再提审、并发重复审核只有一个赢家、匿名/USER 403、普通用户目录不显示 PENDING_REVIEW/REJECTED、已发布概率/库存冻结。

## Boundaries and Success Criteria

- Always：状态迁移与配置冻结原子；审核人来自 ActorProvider；拒绝不泄露草稿给其他 USER；保留每次审核理由和操作者。
- Ask first：公开审核、自动审核、用户付费发布、真实履约供应商和线上风控策略。
- Never：普通 USER 或 Agent 直接发布；批准绕过配置校验；将单节点本机测试称为公开平台安全证明。
- Success：通过后才出现在 USER 活动列表；拒绝有理由；重复状态操作不产生重复/矛盾决定；原抽奖链回归。
