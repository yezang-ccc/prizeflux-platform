# Spec: harness-runtime（2026-09-24 已实现并通过回归）

对应已确认的聚焦能力地图 `harness-runtime`。本规格只覆盖最小 Agent 控制循环，不把 PrizeFlux 工具授权、RAG、SSE、页面或真实模型并入首批。

## Objective

在现有 Java 17 `prizeflux-ai` 模块内实现可独立测试、可逐步阅读的 Agent Harness 核心。输入用户目标后，模型每轮只能返回“最终回答”或“请求调用一个工具”；运行器记录消息与动作，执行受控端口，再把工具结果交回模型，直到结束或触达限制。参考 mini-swe-agent 的简洁循环，但不提供 Bash、任意代码或文件系统执行能力。

主要使用者是项目学习者和后续 PrizeFlux 只读运营助理。首批用确定性 Fake Model 跑通，不需要密钥、网络、数据库或 Docker。成功不等于真实模型效果；此切片只证明循环及停止/失败语义。

## Tech Stack

- Java 17，现有 Maven Reactor、JUnit 5；纯运行核心不依赖 Spring MVC、数据库、Kafka 或具体模型 SDK。
- 首批只加入项目现有依赖可管理的测试支持；如需新的运行依赖，先在实施计划中列出用途和版本，不隐式引入框架。

## Commands

从仓库根目录在 PowerShell 执行：

```powershell
cd backend
.\mvnw.cmd -pl prizeflux-ai -am -DskipTests package
.\mvnw.cmd -pl prizeflux-ai -am test
```

首批交付为可运行的 JUnit 演示而非独立进程；CLI 属于 `harness-evidence`，所以本阶段没有虚构的 `run` 命令。集中回归仍使用 `cd backend; .\mvnw.cmd -q test`，只在批次完成后运行。

## Project Structure

```text
backend/prizeflux-ai/src/main/java/com/prizeflux/ai/harness/
  model/         消息、模型响应、调用请求等不可变类型
  application/   Agent 循环、运行限制、终止结果及端口
backend/prizeflux-ai/src/test/java/com/prizeflux/ai/harness/
  application/   Fake Model 与循环行为测试
```

`harness-safety` 将来实现工具注册与权限端口；本模块只定义调用该端口的最小接口，不包含业务 HTTP 客户端。运行记录保留必要的动作/结果供测试；持久化会话与可导出轨迹属于 `harness-evidence`。

## Code Style

使用明确的不可变类型、构造时校验和穷尽分支；不把模型文本解析成命令执行。例如：

```java
public record ToolCall(String name, Map<String, String> arguments) {
    public ToolCall {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("tool name is blank");
        arguments = Map.copyOf(arguments);
    }
}
```

端口名表达能力（如 `ModelPort`、`ToolExecutor`），不把 Fake Model 的分支逻辑写入运行器。限制值与 `Clock` 从外部注入；循环中不依赖静态全局状态。

## Testing Strategy

- JUnit 5 的确定性测试至少覆盖：直接回答、一次/多次工具调用、工具返回错误、模型返回不合法动作、达到轮数或工具调用上限、截止时间到达、同一 Fake 输入可重复回放。
- 测试断言模型调用次数、工具调用次数、消息顺序和终止原因；尤其验证触达上限后没有额外工具调用。
- 纯单元测试不启动 Spring/MySQL/Kafka；阶段末再参与全后端集中回归。首批没有未经测量的性能或覆盖率百分比承诺。

## Boundaries

- Always：校验模型动作和参数形状；每轮检查轮数、工具次数及时间预算；对失败给出明确终止原因；不把原始异常/敏感内容直接当作最终回答。
- Ask first：引入付费真实模型/密钥、添加可访问外部系统的工具、变更已存在业务或认证契约、引入重型 Agent 框架。
- Never：执行任意 Shell/SQL/文件写入；让模型决定用户身份或授权；让模型参与抽奖/库存写入；在日志或测试产物中保存密钥。

## Success Criteria

1. 无凭据、无网络环境可完成 `prizeflux-ai` 模块构建及上述单元测试。
2. Fake Model 驱动的“请求 → 工具 → 观察 → 最终回答”消息序列可在测试中完整断言；重复运行结果一致。
3. 上限、截止时间、工具错误和无效动作均有可区分终止结果；不会无限循环或在停止后继续调工具。
4. 运行核心不导入 Campaign/Worker 实现类，不直接读取数据库，不暴露任意执行能力。

## Open Questions

无阻塞问题。第一批工具调用参数用不可变 `Map<String, String>`，后续 `harness-safety` 若需嵌套 JSON 参数，先修订接口契约和测试再扩展；真实模型适配器另行规格化。
