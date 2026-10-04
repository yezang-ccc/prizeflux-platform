# Spec: `campaign-ownership`

状态：用户已确认产品方向并要求实施。依赖与顺序见 [`CAPABILITY-MAP-CREATOR-PLATFORM.md`](CAPABILITY-MAP-CREATOR-PLATFORM.md)。

## Objective

任何已登录 `USER` 都可创建营销抽奖活动并参与其他活动。活动以服务端确认的用户 ID 归属；用户只能枚举、查看、编辑自己的草稿，不能通过改路径或请求体访问别人的活动。运营员继续拥有全局管理能力。

## Contract

- `POST /api/me/campaigns`：USER 创建草稿。请求只有活动名和起止时间，不含 id、owner、role 或状态；ID 自服务端生成，owner 从 `ActorProvider` 取得。
- `GET /api/me/campaigns?before=&limit=` 与 `GET /api/me/campaigns/{id}`：仅当前 owner 可读；其他用户和不存在活动均返回相同 404。
- `PUT /api/me/campaigns/{id}`：仅 owner 可更新 DRAFT/REJECTED 的活动基础信息；REJECTED 修改后回到 DRAFT。PENDING_REVIEW/PUBLISHED/CLOSED 不可改。
- `GET/PUT /api/me/campaigns/{id}/configuration`：仅 owner 且状态 DRAFT/REJECTED；仍复用现有确定性概率/库存规则，published version 冻结不变。
- `POST /api/me/campaigns/{id}/submit`：提交前须存在通过验证的奖品配置；DRAFT → PENDING_REVIEW。
- `POST /api/me/campaigns/validate`：USER 的只读提案校验，复用相同活动/概率/库存规则，不创建或更新任何记录；供 Creator Harness 调用。
- 兼容现有 OPERATOR `/api/campaigns` 接口；仅新自助创建接口使用 owner 限制。历史行 owner 可空，新 user-created 行必须非空。

## Tech, Structure and Commands

沿用 Java 17/Spring Boot 3.5、Campaign application ports、MyBatis/MySQL/Flyway。新增 Flyway V4，不改 V1–V3；给 campaign 增加可空 owner 外键和服务端发号支持。应用编排依赖 `ActorProvider` 和 owner-filtered repository 方法，不把权限只放在 Controller。

```powershell
Set-Location -LiteralPath '.\backend'
$env:JAVA_HOME = 'D:\java\jdk-17'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=CampaignApiIntegrationTest,CampaignTest,CampaignRepositoryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -pl prizeflux-campaign -am -DskipTests package
```

## Code Style

沿用构造器注入、应用服务校验身份、数据库条件查询约束归属的风格：

```java
AuthenticatedActor actor = actorProvider.currentActor();
return campaigns.findOwned(campaignId, actor.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
```

客户端 owner、资源存在性、UI 路由均不是授权依据。

## Testing Strategy

纯应用测试验证状态门槛；真实 MySQL + MockMvc 验证发号、owner 写入/查询、跨用户 404、伪造 owner 无效、配置仅可编辑草稿、legacy API 兼容及 Flyway 对历史行的兼容。必须证明 MySQL 最终过滤 owner，不接受先全量查询再由前端筛选。

## Boundaries

- Always：owner 从 JWT Actor 读取；repository 查询同时包含 campaign ID 和 owner ID；发布/抽奖/库存原不变量保持。
- Ask first：多租户组织、付费计划、KYC/第三方身份验证、生产公网部署。
- Never：信任客户端 userId/creatorRole，跨 owner 暴露草稿/奖品配置，以 Redis 作为活动归属事实源。

## Success Criteria

USER 可创建 owner 绑定的服务端 ID 草稿，管理自己的 DRAFT/REJECTED 活动和配置；无法枚举或修改其他用户活动；同一 USER 仍可抽奖；OPERATOR 旧管理能力保留；后端 owner/状态集成测试通过。
