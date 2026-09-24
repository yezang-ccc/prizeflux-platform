# Spec：D2-05 活动应用服务与 REST API

状态：规格及其中七项假设已于 2026-09-23 获学习者明确批准；下一阶段为实施计划，计划批准前不修改生产代码、测试或数据库迁移。归属既有能力地图 `campaign-core`，是一个可独立验收的活动管理纵向切片，不新增能力地图模块。

## 0. 待批准假设

1. 沿用 D2-04 的调用方提供正数业务 `id`，本轮不改为数据库自增或新增 ID 生成器。
2. 创建请求包含 `id`、`name`、`startsAt`、`endsAt`；新活动始终从 `DRAFT` 创建，客户端不能直接指定状态。
3. “修改活动”限定为 `publish` 和 `close` 两种已存在的领域状态迁移；本轮不编辑名称、时间，不实现通用 `PUT/PATCH`。
4. REST 路径采用 `/api/campaigns`：创建、按 id 查询、发布、关闭四个入口。
5. 为符合 HTTP 语义，在共享 Contract 增加通用 `RESOURCE_NOT_FOUND` 和 `CONFLICT` 错误码，分别映射 404、409；非法状态迁移继续使用 `BUSINESS_RULE_VIOLATION` 映射 422。
6. 状态持久化使用“业务 id + 期望旧状态”的条件更新。受影响行数为 0 时返回冲突，避免把并发期间已变化的状态无条件覆盖；本轮不因此宣称完成事务或并发安全。
7. API 集成测试使用 MockMvc 驱动 Spring MVC，并由 Testcontainers 提供真实 MySQL 8.4；它不监听真实 TCP 端口。

批准本规格即批准以上假设。若其中任一项不符合预期，先修订规格，再进入计划。

## 1. 目标与成功定义

为已有 `Campaign` 领域模型和 `CampaignRepository` 持久化端口增加应用层编排与生产 REST 入口，使调用方能够：

- 创建草稿活动；
- 按业务 id 查询活动；
- 发布草稿活动；
- 关闭草稿或已发布活动。

成功意味着请求经过真实 Spring MVC 反序列化与校验、应用服务、领域状态规则、MyBatis Repository 和 MySQL 8.4，并返回统一 `ApiResponse`；Controller 不包含 SQL、持久化对象转换或状态判断。

本轮不包含奖品配置、活动列表/分页、名称和时间编辑、删除、鉴权、幂等请求键、事务用例、并发压力测试、缓存、真实外部端口、远程 CI 或部署。

## 2. HTTP 契约

### 2.1 创建活动

`POST /api/campaigns`

请求：

```json
{
  "id": 1001,
  "name": "秋季抽奖",
  "startsAt": "2026-10-01T00:00:00Z",
  "endsAt": "2026-10-08T00:00:00Z"
}
```

成功：HTTP 201，`PFX-0000 / 成功`，`data` 为 `CampaignResponse`，状态固定为 `DRAFT`。

失败：

| 场景 | HTTP | 错误码 |
|---|---:|---|
| 请求体缺失、JSON/时间无法解析、字段校验失败 | 400 | `INVALID_ARGUMENT` |
| 领域构造不变量失败 | 400 | `INVALID_ARGUMENT` |
| id 已存在 | 409 | `CONFLICT` |
| 未预期数据库/程序错误 | 500 | `INTERNAL_ERROR` |

### 2.2 查询活动

`GET /api/campaigns/{id}`

- 找到：HTTP 200，成功响应携带 `CampaignResponse`。
- `id <= 0`：HTTP 400，`INVALID_ARGUMENT`。
- 合法 id 不存在：HTTP 404，`RESOURCE_NOT_FOUND`。

### 2.3 发布活动

`POST /api/campaigns/{id}/publish`

- 当前为 `DRAFT`：领域返回新 `PUBLISHED` 快照，条件更新数据库成功，HTTP 200 返回更新后的响应。
- 活动不存在：HTTP 404。
- 当前不是 `DRAFT`：HTTP 422，`BUSINESS_RULE_VIOLATION`。
- 读取后数据库状态被其他请求改变，条件更新为 0 行：HTTP 409，`CONFLICT`。

### 2.4 关闭活动

`POST /api/campaigns/{id}/close`

- 当前为 `DRAFT` 或 `PUBLISHED`：领域返回新 `CLOSED` 快照，条件更新成功，HTTP 200。
- 活动不存在：HTTP 404。
- 当前已经 `CLOSED`：HTTP 422。
- 条件更新为 0 行：HTTP 409。

## 3. 应用、领域与持久化契约

### 3.1 应用用例

应用端口只依赖领域对象和 JDK 类型：

```java
public interface CampaignUseCase {
    Campaign create(long id, String name, Instant startsAt, Instant endsAt);
    Campaign get(long id);
    Campaign publish(long id);
    Campaign close(long id);
}
```

`CampaignApplicationService` 使用构造器注入 `CampaignRepository`。它负责加载、缺失判断、调用领域迁移、保存结果和处理条件更新冲突；不接触 Mapper、XML、HTTP 对象或 `CampaignPersistenceRecord`。

### 3.2 Repository 扩展

保留 D2-04 的 insert-only `save` 和 `findById`，增加显式状态条件更新：

```java
boolean updateStatus(long id, CampaignStatus expectedStatus, CampaignStatus targetStatus);
```

Mapper XML 使用显式 SQL：

```sql
UPDATE campaign
SET status = #{targetStatus}
WHERE id = #{id} AND status = #{expectedStatus}
```

返回 1 表示成功；返回 0 表示行不存在或状态已变化。应用服务已先查询，因此 0 按冲突处理。大于 1 属于实现不变量破坏并明确失败。数据库主键冲突由 Repository 适配器转换成 `BusinessException(CONFLICT)`，不把 MyBatis/Spring 数据异常暴露给 Controller。

### 3.3 DTO 与响应

- 扩展 `CreateCampaignRequest`：`@Positive long id`、`@NotBlank/@Size(max=100) String name`、两个 `@NotNull Instant`。
- `CampaignResponse` 包含 `id/name/startsAt/endsAt/status`，只做领域对象到 JSON 的只读投影。
- Controller 返回 `ApiResponse<CampaignResponse>`；失败响应继续保持 `data=null`。

## 4. 错误码与 HTTP 映射

共享 `ErrorCode` 新增：

```java
RESOURCE_NOT_FOUND("PFX-2002", "资源不存在"),
CONFLICT("PFX-2003", "资源冲突")
```

`GlobalExceptionHandler` 映射：

| ErrorCode | HTTP |
|---|---:|
| `INVALID_ARGUMENT` | 400 |
| `RESOURCE_NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `BUSINESS_RULE_VIOLATION` | 422 |
| `INTERNAL_ERROR` | 500 |

错误响应不得包含 SQL、数据库主机、异常类名、堆栈或请求中的敏感原文。服务端未知异常仍记录诊断堆栈；生产日志访问控制不在本轮验收范围。

## 5. 技术栈、结构与代码风格

保持 Java 17、Spring Boot 3.5.16、现有 MyBatis/Flyway/MySQL/Testcontainers 版本，不新增依赖或迁移文件。

预计生产文件：

```text
backend/prizeflux-contract/.../ErrorCode.java
backend/prizeflux-campaign/.../controller/dto/CreateCampaignRequest.java
backend/prizeflux-campaign/.../controller/dto/CampaignResponse.java
backend/prizeflux-campaign/.../controller/CampaignController.java
backend/prizeflux-campaign/.../application/CampaignUseCase.java
backend/prizeflux-campaign/.../application/CampaignApplicationService.java
backend/prizeflux-campaign/.../domain/repository/CampaignRepository.java
backend/prizeflux-campaign/.../infrastructure/persistence/CampaignMapper.java
backend/prizeflux-campaign/.../infrastructure/persistence/MyBatisCampaignRepository.java
backend/prizeflux-campaign/.../resources/.../CampaignMapper.xml
backend/prizeflux-campaign/.../controller/advice/GlobalExceptionHandler.java
```

测试按计划拆分到 Contract、应用服务、Repository 和 API 集成测试，每个微任务最多约 5 个生产/测试文件。Controller 方法只做请求接收、调用用例和响应包装：

```java
@PostMapping
ResponseEntity<ApiResponse<CampaignResponse>> create(
        @Valid @RequestBody CreateCampaignRequest request) {
    Campaign created = campaignUseCase.create(
            request.id(), request.name(), request.startsAt(), request.endsAt());
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(CampaignResponse.from(created)));
}
```

使用 4 空格缩进；公开方法和测试方法用中文注释说明业务意图；禁止 Controller 直接注入 Mapper 或 Repository 实现类。

## 6. 测试策略

### 6.1 Contract 与异常映射

- 新错误码 code/message 唯一且稳定；失败响应 `data=null`。
- `RESOURCE_NOT_FOUND/CONFLICT` 分别映射 404/409。
- 既有 400/422/500、405/415 和响应安全断言保持。

### 6.2 应用服务单元测试

使用内存 Fake/Mockito Repository 验证编排，不启动 Spring 或数据库：

- create 构造 DRAFT 并调用一次 `save`；非法领域参数不保存。
- get 返回活动，缺失抛 `RESOURCE_NOT_FOUND`。
- publish/close 调用领域迁移并使用旧状态作为 expected；0 行抛 `CONFLICT`。
- 非法重复发布/关闭由领域规则拒绝，Repository 不执行更新。

### 6.3 Repository 真实 MySQL 增量测试

- 条件状态更新在 expected 匹配时更新 1 行并可读回。
- expected 不匹配时更新 0 行且不覆盖当前状态。
- 重复主键转换为 `CONFLICT`，原行保持。
- D2-04 的迁移、字段映射和三状态往返测试继续通过。

### 6.4 API 真实 MySQL 集成测试

使用 `@SpringBootTest`、MockMvc 和 Testcontainers MySQL 8.4，覆盖：

- 创建 201，响应字段完整，数据库为 DRAFT；随后 GET 200。
- 发布和关闭后响应、数据库状态一致；非法重复迁移 422。
- 缺失活动 404，重复 id 409，非法 id/字段/JSON 400。
- 条件更新冲突 409；客户端响应不泄露内部异常信息。
- Controller Bean 属于生产扫描；不使用测试夹具代替生产 API。

MockMvc 证明 MVC→应用→MySQL 链路在测试上下文成立，不证明真实端口、防火墙、远程 CI 或生产部署。

## 7. 完整验证命令

计划和任务批准后，实施阶段使用：

```powershell
Set-Location -LiteralPath '.\backend'

.\mvnw.cmd -pl prizeflux-contract '-Dtest=ApiResponseTest,ErrorCodeTest' test
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=CampaignApplicationServiceTest,CampaignRepositoryIntegrationTest,CampaignApiIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate

git -C (git rev-parse --show-toplevel) diff --check
```

目标测试必须实际存在并执行 `tests>0`；具体测试数量以 Surefire XML 为准，不在规格中预造。Docker/容器失败记环境失败，编译失败、行为断言失败和回归失败分别记录。

## 8. 三层边界

### 始终执行

- 规格→计划→任务→实现逐阶段复审；测试先行并保留红绿证据。
- 保留 D2-04 的真实 MySQL 测试和 insert-only 创建语义。
- Controller→UseCase→Repository端口→MyBatis适配器单向依赖。
- 用户逐段阅读并亲自运行目标 API 集成测试后，才关闭项目学习环节。
- 原答、订正、L层级、四类笔记和复习日期分别记录。

### 必须先更新规格

- 改为数据库自增/其他 ID 策略；新增活动字段、列表分页、删除或通用编辑。
- 新增表、列、迁移、依赖或事务边界；改变 D2-03 状态规则。
- 更改错误码文案/HTTP映射；增加鉴权、幂等键、真实端口或部署验证。
- 条件更新无法在现有表和Mapper范围实现，必须引入版本列时。

### 禁止

- Controller 写 SQL、注入 Mapper 或返回持久化 record。
- 将 `save` 偷改成 upsert；用先查后改冒充并发安全。
- 用 H2/Mock Repository 冒充真实 MySQL API 集成证据。
- 删除失败断言、吞数据库异常、返回内部异常消息或提交真实秘密。
- 把 MockMvc 成功写成远程 CI、真实 TCP、生产或完整并发验收成功。

## 9. 完成标准

D2-05 只有同时满足以下条件才完成：

1. Contract、应用服务、Repository条件更新和四个生产 REST 入口按批准任务实现。
2. 应用服务单元测试、真实 MySQL Repository/API 集成测试、全后端测试和六模块 validate 全部成功。
3. 创建、查询、发布、关闭及 400/404/409/422/500 关键路径均有断言。
4. 用户阅读 Controller→UseCase→领域→Repository→Mapper 调用链，并亲自运行目标集成测试回传统计。
5. 用户能解释 DTO/领域双层校验、构造器注入、接口依赖、条件更新冲突以及 MockMvc/真实 MySQL 的证据边界。
6. 评分、错题、知识/项目/面试/复盘笔记和 1/3/7/14 日复习真实登记。

通过只代表本轮活动管理 API 首轮限定闭环；不代表认证、幂等、事务/并发压力、远程 CI、真实端口或部署完成。

## 10. 开放项与规格复审结论

本规格把现有任务中的“创建、查询、修改”具体化为创建、查询、发布和关闭。条件更新可使用现有 `campaign.status` 完成，不需要 V2 迁移或新依赖。新增通用错误码会影响 Contract 与全局异常处理，因此已作为显式批准项列出。

预计总文件超过 5 个，必须在计划阶段按 Contract/应用层、Repository状态更新、REST集成三个顺序切片拆分；任何单个微任务不得暗中覆盖全部文件。完整规格批准后才进入实施计划。
