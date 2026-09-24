# AI HTTP 服务运行说明

## 当前可验证范围

`prizeflux-ai` 是独立可执行 Spring Boot 服务，默认端口 8082，Campaign 地址默认 `http://localhost:8081`。`GET /api/health` 不需要身份；`POST /api/ai/campaign-summary` 需要 Campaign 能验证的 Bearer token，并且只接受服务端 `/api/auth/me` 确认的 `OPERATOR`。模型为 Fake Model，回答始终属于模拟结果，不是实际 LLM 推理。

已验证：AI JAR 成功打包；本地 JAR 的健康接口返回 `{"status":"UP"}`，匿名业务请求返回 HTTP 401；Spring Boot 服务内端到端测试通过（其中使用 Campaign 本地 HTTP 契约桩）。此外，`scripts/smoke-ai-campaign-cross-process.ps1` 已通过：独立 Campaign/AI JVM、临时 MySQL 8.4、Campaign 签发 JWT、AI 再向 Campaign `/api/auth/me` 鉴权并读取真实 MySQL 活动记录。该测试使用 Fake Model，未使用真实模型或生产凭据。

## 可重复的独立进程冒烟

确保 Docker Engine 可用、JDK 17 已安装，且本机端口 13306、8081、8082 未被占用。先构建 Campaign 与 AI 可执行 JAR（仓库当前已构建时可跳过），再从仓库根目录运行：

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17'
.\scripts\smoke-ai-campaign-cross-process.ps1
```

脚本会生成随机一次性凭据并仅在本机进程间传递；创建短生命周期 MySQL 8.4 容器，首次启动 Campaign 时创建临时 OPERATOR，随后关闭 bootstrap 并重启 Campaign，再启动 AI。它验证已认证查询和匿名 401。结束时停止两个 JVM、删除临时数据库容器并恢复当前 PowerShell 原有环境变量；不会修改 `deploy/.env` 或已存在的数据库。脚本只连接本机固定端口；如果这些端口被占用会先失败退出，请勿因此终止不明服务。

该脚本仅为本机集成冒烟，不替代 Maven 自动化测试、Kafka/Redis 实验或公网部署验证。

## 依赖和安全

- 本地 MySQL 可用；现有 [foundation Compose](../../deploy/compose.foundation.yml) 只提供 MySQL，不提供 Kafka。
- Campaign 设置 `local-full` Profile 时，如果仅做只读 HTTP 验证，可显式关闭 Kafka Publisher 与 Outbox Relay；这样验证的不是发奖事件链路。
- Campaign 的 `PFX_JWT_SECRET` 至少 32 字节，只从本地进程环境读取。JWT、密码只保存在个人环境/密码管理器；不得粘贴到仓库、日志、截图或交接记录。
- 若首次启动需要创建运营用户，按 `.env.example` 的一次性 Operator Bootstrap 环境变量，在隔离的本地数据库初始化后立刻停止进程并关闭 Bootstrap，再以同一密钥重启。不要把运营密码或令牌写入此文档。
- 实际活动必须已经存在，否则虽然认证通过，活动只读工具仍会返回未找到。

## 启动方式

1. 复制 `deploy/.env.example` 为被 Git 忽略的 `deploy/.env`，替换本地数据库密码；启动 MySQL：

   ```powershell
   docker compose --env-file deploy/.env -f deploy/compose.foundation.yml up -d
   ```

2. 在 Campaign 终端设置 `PFX_DB_URL`、`PFX_DB_USER`、`PFX_DB_PASSWORD` 和 `PFX_JWT_SECRET`，按需只在首次初始化时临时设置 Operator Bootstrap 变量。然后在 `backend` 运行：

   ```powershell
   $env:JAVA_HOME = 'D:\java\jdk-17'
   .\mvnw.cmd -pl prizeflux-campaign spring-boot:run '-Dspring-boot.run.profiles=local-full' '-Dspring-boot.run.arguments=--prizeflux.kafka.enabled=false,--prizeflux.outbox.relay.enabled=false'
   ```

   首次初始化完成后，停止 Campaign、移除 Operator Bootstrap 开关和凭据，再以相同 DB/JWT 环境重启。Campaign 默认监听 8081。

3. 在另一终端启动已构建的 AI JAR：

   ```powershell
   $env:PFX_CAMPAIGN_BASE_URL = 'http://localhost:8081'
   java -jar backend\prizeflux-ai\target\prizeflux-ai-0.1.0-SNAPSHOT.jar
   ```

4. 用不回显 token 的本地 HTTP 客户端完成登录，然后调用 `/api/ai/campaign-summary`，请求体只传 `campaignId`。不要将 token 放进命令行历史或提交文件。健康检查可使用 `Invoke-RestMethod http://localhost:8082/api/health`。

上述分步命令是手动启动方式；H5 的本机独立进程验证已由可重复脚本完成并记录。手动流程本身不等于脚本证据，也不代表生产部署。
