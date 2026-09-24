# Spec: harness-evidence（首批：可运行演示与固定评测）

状态：2026-09-24 按用户授权自行复审后实施。本规格对应聚焦地图 `harness-evidence` 的首批；持久会话、真实模型成本与质量评测将继续迭代，不能因首批完成就将整个模块标为完成。

## Objective

提供不依赖密钥、网络、Docker 的命令行演示，清楚展示用户目标→模型工具请求→白名单执行→观察→最终回答。提供固定场景评测与简短轨迹，使他人可以复现“正常读、越权拒绝、调用上限”三种结果。首批业务数据是明确标注的模拟活动，不冒充真实 Campaign 联调。

## Tech Stack

Java 17、现有 `prizeflux-ai`、JUnit 5；仅使用已引入的依赖，不引入新的 Agent 框架。

## Commands

```powershell
cd backend
.\mvnw.cmd -pl prizeflux-ai -am test
.\mvnw.cmd -pl prizeflux-ai -am -DskipTests package
java -cp prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo
java -cp prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo --eval
```

评测模式有失败时非零退出；批末合并回归在其他批次一起运行，不为每个演示文件重跑 MySQL/Kafka。

## Project Structure

```text
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/demo/
  HarnessDemo.java、ScriptedModel.java、DemoEvaluation.java
backend/prizeflux-ai/src/test/java/com/prizeflux/ai/harness/demo/
  HarnessDemoTest.java
docs/learning/harness-demo.md
```

## Code Style

场景以不可变运行结果和明确状态判断，不依据模型自然语言“看上去正确”判分。例如 `result.status() == RunStatus.COMPLETED && result.toolCalls() == 1`。输出中始终注明 `SIMULATED`。

## Testing Strategy

JUnit 测试固定演示输出、三类评测结果与无凭据运行；命令行执行后记录退出码和实际输出。Fake 数据与真实 HTTP 客户端完全分开，避免误把模拟测试写成跨服务证据。

## Boundaries

- Always：模拟标签、固定可重放场景、失败非零退出、轨迹不含令牌。
- Ask first：真实模型费用、公网部署、外部数据接入。
- Never：把模拟活动称为数据库实况；在演示输出令牌；在 Demo 中开放 Shell/写工具。

## Success Criteria

1. 上述两条 `java -cp` 命令在无密钥环境可运行并明确输出模拟标签。
2. 固定评测至少覆盖正常读取、越权拒绝、工具调用上限；重复运行结果一致。
3. 失败场景不会执行受保护业务回调，也不会在输出中包含令牌。

## Open Questions

真实模型、会话持久化、上下文截断与成本统计属于 `harness-evidence` 后续批次，首批不宣称完成这些能力。
