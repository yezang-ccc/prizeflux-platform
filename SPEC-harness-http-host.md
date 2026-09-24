# Spec: harness HTTP 宿主（H1～H5 本机验证完成）

对应聚焦能力 `harness-safety` / `harness-evidence`。首版仅 Fake Model，真实模型 API key/费用不在授权范围。

## 目标与边界

把 `prizeflux-ai` 打成独立可运行 Spring Boot 服务，提供 `POST /api/ai/campaign-summary`。每次请求都将 Bearer token 交给 Campaign `GET /api/auth/me` 验证，只有服务端返回的 OPERATOR 可继续；再将同一 token 经固定 GET 查询活动。模型不能提供身份、令牌或 URL。确定性假模型只调用已注册的 `campaign.summary`，响应标记 `simulated=true`。

AI 服务不保存密钥或会话，不复制 Campaign JWT 验签密钥。Campaign 默认端口为 8081，AI 默认端口为 8082；Campaign 地址可用 `PFX_CAMPAIGN_BASE_URL` 覆盖。未认证 401、角色不足 403、上游不可用 502、输入错误 400；响应不包含异常、令牌或上游原始响应。CORS/公网部署/真实模型另行建设。

## 技术与结构

Java 17、Spring Boot 3.5、Web MVC、Jackson、现有 Harness 核心。`controller → application → model/safety/campaign ports`；配置层创建固定基地址客户端。AI 运行独立进程，地址从 `PFX_CAMPAIGN_BASE_URL` 配置，默认只指向本机 Campaign。

新增 `PrizeFluxAiApplication`、`AiCampaignController`、请求/响应 DTO、`CampaignIdentityClient` 与 HTTP 实现、Fake 演示用例及配置。控制器仅协调认证、只读工具、AgentRunner，不接触数据库。

## 验收与验证

- 无 token / 无效身份 / USER 不触发活动 HTTP；仅 OPERATOR 执行已固定活动 GET。
- Mock Campaign HTTP 测试验证 `/me` 再 `/campaigns/{id}` 顺序与相同 Bearer token；写请求计数恒为 0；伪造 userId/URL 不生效。
- 成功输出只含回答、模拟标签和精简 trace；固定错误映射不泄密。
- `cd backend; .\mvnw.cmd -pl prizeflux-ai -am test`、打包并以 `java -jar prizeflux-ai/target/prizeflux-ai-*.jar` 启动健康接口；里程碑末再全后端回归。

当前记录：H1～H5 本机验证完成。除服务内测试桩之外，已用一次性 MySQL 8.4 和两个独立 JVM 验证真实 Campaign 登录签发 JWT、AI 经 Campaign `/api/auth/me` 鉴权并固定 GET 查询刚创建的活动，匿名 AI 请求返回 401。Fake Model 明确返回 `simulated=true`。这不代表真实模型、公网部署、远程 CI 或生产性能验证。完整环境及命令见 [AI 服务运行说明](docs/runbooks/ai-service.md) 和 [可重复冒烟脚本](scripts/smoke-ai-campaign-cross-process.ps1)。

## 安全规则

允许：健康探针公开、AI 入口强制上游身份验证、令牌仅内存转发。拒绝：固定演示身份、无密钥放行、把 Actor 放在请求 DTO、模型决定工具权限、重定向、向调用者返回上游异常或令牌。
