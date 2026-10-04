# Spec: `campaign-read-api`

状态：参与用户目录与 OPERATOR 查询已实现；新增 Creator owner query 扩展见 [`SPEC-campaign-ownership.md`](SPEC-campaign-ownership.md)，当前未完成。历史验证证据见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

## Objective

给参与用户、活动创建者和运营员提供前端所需的活动/奖品查询能力。读取结果必须按服务端认证身份过滤：USER 公共目录不枚举草稿；创建者仅读本人草稿；OPERATOR 可查看全站活动和当前配置。接口只读，不改变活动、库存、次数或抽奖结果。

## Contract

- `GET /api/available-campaigns?before={id}&limit=20`：仅 `USER`。返回 PUBLISHED 且未结束的活动（包含尚未开始活动），按稳定游标分页；不返回 DRAFT/CLOSED。
- `GET /api/ops/campaigns?before={id}&limit=20`：仅 `OPERATOR`。返回所有状态活动，按稳定游标分页。
- `GET /api/campaigns/{id}/prizes`：USER 仅可读 PUBLISHED 活动的奖品 ID/名称/公开概率；OPERATOR 可读其管理范围内的配置。不给参与端返回实时库存，避免把展示数误作库存承诺。不得暴露数据库行或内部字段。
- `GET /api/ops/campaigns/{id}/configuration`：仅 `OPERATOR`，供编辑草稿使用，返回每日参与上限与奖品 ID/名称/库存/概率；发布后只读，不能借 GET 绕过配置冻结。
- `GET /api/me/campaigns` / `GET /api/me/campaigns/{id}` 与 `/api/me/campaigns/{id}/configuration`：Creator 扩展，仅当前 USER 的 owner-scoped 查询；详细写接口和资源隐藏语义见 `SPEC-campaign-ownership.md`。
- 现有 `GET /api/campaigns/{id}` 保持响应兼容，同时 USER 读取 DRAFT/CLOSED 活动时按“不存在”处理，不泄露草稿存在性；OPERATOR 可查看。
- 列表 `limit` 缺省 20、范围 1–100；`before` 游标为正数 ID。响应继续包装 `ApiResponse`，列表使用稳定 `items + nextCursor` 形状。HTTP 未认证 401、角色不足 403、隐藏资源 404、非法游标 400。

本规格的活动/奖品字段是 HTTP 合同，不承诺库存实时展示；参与端展示冻结的公开概率，不包含可变库存数量。所有字段都来自 Campaign 已冻结的配置，不构造假数据。

## Tech Stack and Project Structure

Java 17、Spring Boot 3.5、现有 Contract、Campaign application/domain/repository、MyBatis/MySQL、Flyway 既有表；若现有表无法支持查询，仅新增向后兼容迁移，不修改历史迁移。

- Controller/response DTO：`backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/controller/`
- 查询用例与端口：`.../application/`
- SQL 适配器：`.../infrastructure/persistence/`
- 测试：`backend/prizeflux-campaign/src/test/java/`

查询依赖由 controller → application → domain/port → infrastructure 实现。不得让 Controller 拼 SQL，不得让列表读取隐式触发配置写入。

## Code Style

公开响应采用不可变 record；调用时显式传入 `Actor`/服务端身份，不接受 query/body 中的 `userId`。以正序/倒序和 `before` 游标的一致查询条件实现稳定分页；错误使用既有错误映射，不泄露 SQL/异常。

## Commands

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am test
.\mvnw.cmd -DskipTests validate
git -C (git rev-parse --show-toplevel) diff --check
```

集成数据库测试需 Docker Engine；批次结束集中跑全后端，不按每个接口重复全量回归。

## Testing Strategy

- USER 只看得到发布且未结束活动和已发布奖品；尝试读取草稿得到不泄露存在性的 404。
- OPERATOR 可查询草稿/已发布/关闭活动及可编辑草稿配置；USER 调用运营 GET 得 403。
- 匿名请求 401；每个失败请求无查询外副作用，所有响应不含内部 SQL/异常。
- 分页不重复、不遗漏边界行；空集、非法/过界 limit、非法游标明确处理。
- 配置查询与列表不改变库存、活动状态、配置版本；已发布配置保持冻结。
- 最终 Campaign 模块和批末整仓测试通过，记录实际测试汇总与 Docker 状态。

## Boundaries

- Always：沿用 JWT/Actor；按活动状态与角色做服务端资源授权；复用现有响应和历史迁移；保留现有未提交测试改动。
- Ask first：新增数据库表/外部依赖、改变现有端点 DTO/错误码、开放匿名读取/公开部署。
- Never：信任客户端 `userId`/role；让 USER 读取 DRAFT 配置；把库存读数/缓存当成扣减权威；直接访问 Mapper 越过用例层。

## Success Criteria

所有上述授权、状态过滤、分页和只读行为有可重复测试，Swagger/README 或 API 文档与真实 DTO 一致。奖品概率来自已发布的冻结配置；参与端不显示实时库存。活动 ID 沿用现有 `long` 合同，UI 输入限制于 JavaScript 安全整数范围。
