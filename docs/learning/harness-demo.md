# Agent Harness 离线演示

这一阶段学习的是 Harness 控制循环，不是“接了大模型就能聊天”。运行器把模型给出的动作限制为最终回答或一个类型化工具请求；工具执行器再按服务端身份、白名单和参数规则决定是否执行。模型文本本身没有授权能力。

在 Windows PowerShell 中从仓库根目录运行：

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17'
cd backend
.\mvnw.cmd -pl prizeflux-ai -am test
.\mvnw.cmd -pl prizeflux-ai -am -DskipTests package
java -cp prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo
java -cp prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo --eval
```

演示会依次显示用户请求、`campaign.summary` 工具调用、工具观察和最终回答。所有活动数据都带 `SIMULATED` 标签，来自内存中的固定样例，**没有调用真实 Campaign、MySQL 或模型服务**。`--eval` 对正常读取、未知写工具拒绝和调用上限做固定断言；它验证流程，不代表自然语言质量、线上吞吐或真实跨服务权限已验收。

真实业务只读适配器在 `HttpCampaignReadClient`。独立 AI→Campaign + MySQL/JWT 本机联调由 [跨进程冒烟脚本](../../scripts/smoke-ai-campaign-cross-process.ps1) 另外验证；脚本使用 Fake Model 并明确输出 `simulated=true`。离线 Demo 本身仍然只使用内存假数据，不能把它说成真实业务演示、真实 LLM 或生产部署。不要把真实令牌放在命令行、文档或 Git 提交中。

## 最近一次本机运行记录

2026-09-25，Windows / JDK 17，使用已构建的 `prizeflux-ai/target/classes`，从仓库根目录执行：

```powershell
java -cp backend/prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo
java -cp backend/prizeflux-ai/target/classes com.prizeflux.ai.harness.demo.HarnessDemo --eval
```

两条命令均退出码 0。普通演示到达 `status=COMPLETED`，依次输出模拟用户请求、`campaign.summary` 调用、工具观察和最终回答。固定评测输出：`authorized-read` 为 `COMPLETED`（2 轮、1 次工具调用、1 个已执行操作）；`forbidden-write` 为 `COMPLETED`（模型尝试 1 次，实际操作 0 次）；`tool-limit` 正确停止于 `TOOL_CALL_LIMIT`。这是可复现的离线 Harness 证据，不是自然语言质量评测或真实 Campaign 查询。
