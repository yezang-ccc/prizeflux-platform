# Spec: harness-safety（2026-09-24 已实现并通过回归）

对应聚焦能力地图 `harness-safety`。假设：AI 请求入口将来提供经过服务端验证的 `USER`/`OPERATOR` 身份；本切片只实现白名单、参数和权限边界，不在此切片新增 Campaign HTTP 客户端、数据库权限或真实模型。用户已授权自行复审计划并直接执行，关键边界仍按本规格记录。

## Objective

让模型只能提出类型化的只读工具请求，由服务端注入的可信身份与固定注册表决定是否执行。未知工具、角色不足、身份伪造参数、参数形状错误、过大结果和工具异常均安全拒绝；拒绝返回有限错误代码，不泄露异常细节。此能力是可上简历的 Agent 演示所需安全底座，但不等同外部业务授权已经完成。

## Tech Stack

Java 17、现有 `prizeflux-ai` Maven 模块、JUnit 5。安全核心不依赖 Spring Security 或 Campaign 实现类；后续适配器从真实安全上下文构造身份。

## Commands

```powershell
cd backend
.\mvnw.cmd -pl prizeflux-ai -am '-Dtest=AuthorizedToolExecutorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -pl prizeflux-ai -am test
```

批次末才运行完整 `cd backend; .\mvnw.cmd -q test`；若当前 PowerShell 未设置 Java 环境，先将 `JAVA_HOME` 指向已安装的 Java 17。

## Project Structure

```text
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/safety/
  Actor.java、Role.java               可信服务端身份
  ArgumentRule.java、ToolDefinition.java  工具参数与角色契约
  ReadOnlyOperation.java、AuthorizedToolExecutor.java  执行边界
backend/prizeflux-ai/src/test/java/com/prizeflux/ai/harness/safety/
  AuthorizedToolExecutorTest.java      正常与恶意请求矩阵
```

## Code Style

不可变工具描述与显式拒绝原因；只将可信 Actor 传入实现，不从模型参数建立身份。例如：

```java
ToolResult result = executor.execute(
        new ToolCall("campaign.summary", Map.of("campaignId", "1005")),
        Duration.ofSeconds(2));
```

工具名、允许的参数名与值规则由应用代码注册，模型文本不能动态扩大注册表。错误代码面向模型和审计可重复断言，异常消息不出边界。

## Testing Strategy

JUnit 5 覆盖授权成功、未知工具、USER 访问运营工具、模型伪造 `userId`/`role`、多余/缺失参数、值格式/长度错误、异常信息脱敏、过大结果与不可用时间预算。用计数器断言所有拒绝路径都没有调用底层操作；受权路径必须向操作传入服务端 Actor。无需 Docker 或真实模型。

## Boundaries

- Always：默认拒绝；工具名必须精确匹配；执行前验证角色与全部参数；限制结果长度；异常转固定安全错误码；实际 HTTP 适配器还须做资源级授权。
- Ask first：加入外部系统调用、真实模型密钥、修改 Campaign 认证契约、启用任何写入工具。
- Never：从 ToolCall 参数接受身份/角色；执行任意 Shell/SQL；让模型更改中奖或库存；向模型泄漏工具异常、凭据或未授权资源内容。

## Success Criteria

1. 白名单以外的工具、未经许可的角色及无效参数在操作回调前被拒绝。
2. 模型不能通过 `userId`、`role` 等参数改变操作接收到的服务端 Actor。
3. 异常和过大结果不会把原始敏感数据传回模型；所有错误结果有稳定代码。
4. 至少八类安全/失败测试重复通过，并与已有 `harness-runtime` 测试一起通过模块回归。

## Open Questions

真实 Campaign 只读查询路径及其资源级权限在后续业务适配规格中确定；本切片不假装已实现该授权。
