# Harness 高 Star 项目参考笔记

核对日期：2026-09-24。Stars 会变化；这里的数字仅记录核对当日 GitHub 官方仓库页。只参考公开架构/文档，不复制源码、提示词或测试数据。

| 官方项目 | 当日 Stars | 对 PrizeFlux 有用的设计 | 不照搬的部分 |
|---|---:|---|---|
| [OpenHands](https://github.com/OpenHands/OpenHands) / [Software Agent SDK](https://github.com/OpenHands/software-agent-sdk) | OpenHands 约 89.0k | ConversationState 将事件日志作为历史来源，并从事件构造活动视图；持久化边界、锁和状态恢复值得学习。 | 完整事件树/分支、插件生命周期、沙箱/工作区与生产存储对当前 Fake Model MVP 太重。 |
| [DeepAgents](https://github.com/langchain-ai/deepagents) | 约 29.7k | 区分 graph checkpoint 与文件/长期记忆 backend；上下文增长时保留摘要并把旧消息 offload，而不是混成同一状态。 | 不接入 LangGraph 中间件、子 Agent、VFS、真实模型压缩或 LangSmith；先让用户看懂原生 Java 控制流。 |
| [mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent) | 约 7.9k | 线性追加的 messages/trajectory 与模型输入一致，易回放、调试和评测；适合作为当前 Harness 最小基线。 | 不开放 Bash 工具；其任务环境、独立 subprocess 和 Python API 与只读 Campaign 用例不同。 |

对当前首批决策：会话原始事件只追加；上下文窗口是受字符预算约束的派生视图，窗口裁剪不能改写历史。SessionStore 先用单进程内存适配器，所以重启会丢、多个 AI 实例不共享。该诚实边界比未有加密/恢复/一致性证据就把会话写入普通文件或共用 Campaign 库更适合现阶段。将来如果要长期/多实例会话，先单独设计 AI 自有 durable store、加密、保留期、并发写与恢复契约。

资料：GitHub 官方仓库 README/架构页：OpenHands；[OpenHands SDK ConversationState](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/state.py) 与 [EventLog](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/event_store.py)；[DeepAgents 架构说明](https://github.com/langchain-ai/deepagents/blob/main/libs/ARCHITECTURE.md) 和 [Summarization Middleware](https://github.com/langchain-ai/deepagents/blob/main/libs/deepagents/deepagents/middleware/summarization.py)；[mini-swe-agent README](https://github.com/SWE-agent/mini-swe-agent)。
