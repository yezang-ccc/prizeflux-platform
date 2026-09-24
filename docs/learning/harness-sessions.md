# Harness 多轮会话与上下文

## 运行边界

AI Session API 只允许 Campaign 验证过的 `OPERATOR`。每次创建、发送、读取或删除请求都会重新向 Campaign 验证 Bearer JWT；会话 owner 从服务端 Actor 取得，客户端不能指定。会话 ID 是服务器生成的 UUID。会话消息通过 `expectedVersion` 做乐观并发控制：两个请求同时基于同一版本时，只能有一个追加成功。

当前 store 是 AI 进程内存实现：最多每位 owner 10 个会话、整个 JVM 1,000 个会话、每会话 100 条消息/100,000 个字符，空闲 30 分钟过期。重启 AI 或切换到另一实例会丢失/看不到会话；这不是持久化或分布式 session。会话不保存 JWT。

HTTP 入口：

- `POST /api/ai/sessions` 创建会话。
- `POST /api/ai/sessions/{id}/messages` 发送 `{ "campaignId": 1005, "question": "总结活动", "expectedVersion": 0 }`。
- `GET /api/ai/sessions/{id}` 读取追加式事件；owner 不匹配与不存在统一返回 404。
- `DELETE /api/ai/sessions/{id}` 删除自己的会话。

上下文窗口是从完整事件历史派生出来的“最近完整交互”视图，不会删改历史。窗口预算是最多 24 条消息、12,000 个字符，不是 tokenizer token 数；新问题必须完整放入窗口，不能放下时返回客户端错误，不会静默截断。超出窗口的旧消息仍留在会话历史，只是不发送给本轮 Fake Model。

## 本机跨进程验证

先在仓库根目录设置 JDK 17，构建服务 JAR；然后执行：

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17'
cd backend
.\mvnw.cmd -pl prizeflux-campaign,prizeflux-ai -am -DskipTests package
cd ..
.\scripts\smoke-ai-campaign-cross-process.ps1
```

脚本需要已启动的 Docker Engine；它拒绝占用中的 13306、8081、8082 端口，使用临时 MySQL、一次性测试凭据和两个独立 JVM，结束时清理临时进程/容器。脚本不打印 JWT/密码。成功会输出 `PASS`，证明 Campaign 签发的 JWT 经 AI 每次请求重新验证、AI 能跨 JVM 读取真实 MySQL 活动，并在 AI 单 JVM 会话内完成两轮追加式交互。Fake Model 仍会显示 `simulated=true`；这不证明真实模型质量、AI 重启后恢复、多实例共享或公网部署。

离线 Harness 演示见 [harness-demo.md](harness-demo.md)，高星项目的设计参考与不采纳项见 [参考笔记](../architecture/harness-reference-notes.md)。
