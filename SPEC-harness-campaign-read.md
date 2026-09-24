# Spec: harness-safety / Campaign 只读接入

状态：2026-09-24 已实现并通过本地模拟 HTTP 与合并回归。此规格是 `harness-safety` 的业务适配子切片，不改变已实现的最小循环契约。

## Objective

提供唯一的首批业务工具 `campaign.summary`。模型只能给正整数活动 ID；可信宿主提供已认证的 OPERATOR 身份与短期令牌。适配器只向配置好的 PrizeFlux Campaign 基地址执行 `GET /api/campaigns/{id}`，读取公开响应中的活动 ID、名称和状态，不调用抽奖/配置写入口。首批通过本地模拟 HTTP 测试验证，不要求真实账号或运行中的 Campaign 服务。

## Tech Stack

Java 17 `HttpClient`、Jackson（由 Spring Boot 3.5 BOM 管理）、现有 `prizeflux-contract` 错误码、JUnit 5 的 JDK 本地 HTTP 测试服务。

## Commands

```powershell
cd backend
.\mvnw.cmd -pl prizeflux-ai -am '-Dtest=CampaignSummaryToolTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -pl prizeflux-ai -am test
```

批末再运行全后端 `cd backend; .\mvnw.cmd -q test`。本地环境需先设置 `JAVA_HOME` 为 Java 17 安装目录。

## Project Structure

```text
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/campaign/
  CampaignSummary.java、CampaignReadClient.java
  HttpCampaignReadClient.java、CampaignSummaryTool.java
backend/prizeflux-ai/src/test/java/com/prizeflux/ai/harness/campaign/
  CampaignSummaryToolTest.java
```

## Code Style

HTTP 基地址与令牌只来自可信宿主配置，不来自 `ToolCall`；校验 ID 后构造固定路径。例如 `new ToolCall("campaign.summary", Map.of("campaignId", "1005"))`，不接受模型传入 URL、方法、角色或 Authorization。

## Testing Strategy

本地 HTTP 服务断言只收到 GET 和正确的 Bearer 头；覆盖正常响应、匿名/越权拒绝、模型伪造身份/URL、非成功状态、重定向、错误响应形状、超时预算与过大正文。失败统一进入安全工具错误码，不向模型泄漏令牌或原始响应。测试无需 Docker 或真实凭据。

## Boundaries

- Always：固定目标主机与路径、无重定向、限制超时和响应大小；HTTP 明文只允许 loopback，远端服务必须 HTTPS；业务字段白名单输出；OPERATOR 工具权限与 Campaign 自身 JWT 验证均保留。
- Ask first：增加其他外部系统、真实模型费用、公共入口或修改 Campaign HTTP/认证契约。
- Never：接受模型提供的目标 URL/令牌/userId；执行 POST/PUT/DELETE；把 HTTP 异常和响应原文直接回送给模型。

## Success Criteria

1. 模拟 Campaign 服务只观察到带可信令牌的固定 GET，请求活动 ID 与模型经过验证的参数一致。
2. `USER`、无效参数、伪造身份字段不会触发 HTTP 调用。
3. 401/403/404、重定向、坏 JSON 或超时安全失败，最终工具结果不包含令牌/响应原文。
4. 模块定向与合并回归通过。真实跨服务鉴权尚未在独立进程联调，本阶段不得声称完成。

## Open Questions

宿主 AI HTTP 入口还未实现；它必须从验证过的安全上下文拿 Actor 与令牌并一起构造执行器，不能让客户端自行指定 Actor。该入口、真实联调与会话评测属于后续交付。
