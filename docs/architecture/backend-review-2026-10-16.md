# 后端定向审查：2026-10-16

## 范围和验证边界

基于 HEAD `45e8b1a7` 及当前工作树，审查 Campaign 抽奖事务、MySQL 排队任务、Outbox Relay、Worker 消费/履约重试、活动审核与 JWT 授权。保留已有 Agent/UI 未提交改动与面试题库；本批不修改业务代码。不等同全仓安全认证或生产验收。

实际验证命令（backend 目录，JAVA_HOME=D:/java/jdk-17）：

```powershell
.\mvnw.cmd -o -pl prizeflux-campaign,prizeflux-worker -am '-Dtest=OutboxPublisherTest,BoundedOutboxDispatcherTest,FulfillmentConsumerTest,FulfillmentApplicationServiceTest,FulfillmentRetryServiceTest,FulfillmentRetryPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q
```

退出0；6类26项，失败/错误/跳过均0。这些是既有定向单测，不是下列问题的故障复现。`docker version --format '{{.Server.Version}}'` 失败，Docker Desktop Linux Engine 管道不存在；未运行 MySQL/Kafka 集成与进程崩溃演练。源码推导的触发序列与实测明确分开。

## 发现（按优先级）

### R1 / P1：Worker 入账失败可耗尽默认重试并跳过消息

位置：`backend/prizeflux-worker/src/main/java/com/prizeflux/worker/FulfillmentConsumer.java:57`，以及 Worker 的 `application.yml`、`application-local-full.yml`。

Worker 监听器依赖 Boot 默认工厂，没有自己的 CommonErrorHandler/DLT。Campaign 的 DrawQueueKafkaErrorConfiguration 位于另一个运行单元，不会保护独立 Worker。依赖BOM确认 Spring Kafka 3.3.16；该版本默认 DefaultErrorHandler 在初次失败及9次重试后采用日志 recoverer，允许跳过记录，ackAfterHandle默认true。

触发：Outbox已获得broker确认并标SENT；Worker MySQL写入持续失败且重试耗尽。消息可能被默认恢复器跳过，数据库里没有fulfillment记录，FulfillmentRetryService只能扫描已有记录，不能修复此缺口。Broker物理记录未必丢失，但当前消费组正常运行不会自动补回，需要人工重置offset/重发。

建议：Worker显式配置失败分类、退避及持久恢复去向；基础设施失败可暂停/停止容器并告警，或使用确认成功的DLT写入后才跳过。DLT还需重放流程，不能把写入DLT当成履约完成。补测试：持续失败超过默认次数后恢复，事件必须仍可恢复到唯一账本；DLT发布失败不得跳过原消息。

证据：源码与匹配版本框架源码核对，尚无本机broker故障复现。

- [DefaultErrorHandler v3.3.16](https://github.com/spring-projects/spring-kafka/blob/v3.3.16/spring-kafka/src/main/java/org/springframework/kafka/listener/DefaultErrorHandler.java)
- [KafkaMessageListenerContainer v3.3.16](https://github.com/spring-projects/spring-kafka/blob/v3.3.16/spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java)

### R2 / P2：旧直接发布接口未限制为旧运营活动

位置：`backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/application/CampaignConfigurationService.java:88`；`JdbcCampaignConfigurationRepository.java:23`。

SPEC-campaign-review仅允许旧OPERATOR活动保留直接发布。但旧POST /api/campaigns/{id}/publish只要求调用者为OPERATOR，publish只锁定并检查DRAFT，不检查owner_user_id。运营员对普通用户尚未提审、已配好奖品的DRAFT调用旧接口，可直接冻结并发布，且没有recordDecision记录。不是普通USER越权，但绕过了创作者提审及审核审计合同。

建议：在同一事务中识别旧运营活动（当前数据模型的owner约定），只有该类活动可以走旧发布路径；owned活动必须PENDING_REVIEW并走approve。补HTTP集成测试同时证明owned DRAFT被拒绝、旧活动兼容、正常审核生成唯一记录。

证据：调用链与SQL静态确认，尚无本轮HTTP/MySQL复现。

### R3 / P2：活动关闭/到期竞争下，同键请求可能被拒绝而非重放

位置：`backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/application/DrawApplicationService.java:134-147`。

初次findByKey在事务外；事务内先校验活动状态和时间，再insertRequest触发唯一键竞争。可行交错：A预查未命中后暂停；B以相同用户/operation/key完成抽奖并提交；活动关闭或到期；A恢复，先因活动不可抽被拒绝，未走到insertRequest，也不会进入DuplicateDrawRequestException后的重放。不会重复扣库存，但相同已完成请求在竞争下返回错误，偏离幂等重放合同。QUEUED_DRAW共享该链路；是否进一步造成队列终态不一致需专项并发复现，不在本次断言范围。

建议：调整幂等占位与资格检查顺序，或在权威拒绝路径安全复核同键已提交结果；必须保持失败事务外读取、指纹冲突及新请求拒绝语义。补受控交错测试，确保活动关闭后新key失败、同key返回原结果、异指纹仍409。

证据：明确可行的源码执行顺序推导，未执行并发故障复现。

## 已知未完成能力，不混作新增漏洞

- Worker→Campaign发奖状态投影未实现。JdbcDrawRepository插入WON时写PENDING，历史接口读取该列；Worker只更新自己的fulfillment表。当前HistoryView并未把它渲染成已完成发奖，所以不能描述成页面展示错误。此前PROJECT_STATUS已记录延期，本次再次确认。不能宣称用户端发奖状态端到端闭环。
- JWT请求转换读取签名claims，不查询当前用户状态；有效期内禁用账号/角色变化不能保证即时生效。默认15分钟、上限1小时，不是即时撤销。属于既有边界。
- 模拟履约不是外部供应商幂等交付。claim token保护本地状态更新，不能单独阻止已失租约的执行者产生外部副作用；真实供应商需稳定业务幂等键、查询与对账协议。
- 历史查询使用(user_id,id游标)，现有索引为(user_id,created_at,id)，不能直接声称完全利用索引消除排序。先EXPLAIN与真实数据测量，再决定是否新增索引。
- 已有offset回退重放测试不等同“DB提交后offset确认前硬崩溃”实证；本机单Broker容量不外推生产。

## 面试讲述注意点（依据当前实现，不是个人验收记录）

| 主题 | 应讲清的机制 | 常见追问/不能夸大的部分 |
|---|---|---|
| 抽奖事务 | 同一MySQL事务写请求、次数、库存、结果、中奖Outbox；条件UPDATE防负库存 | 回包丢失如何恢复？同key查询/重试，不换key重抽；R3竞争边界待修 |
| 幂等 | 唯一键包含operation/user/key，指纹防同键异参；Java先查不能替代唯一约束 | 重复请求与并发请求区别；冲突事务外读赢家结果 |
| 概率/库存 | 先选候选再扣库存；售罄不重抽，NO_PRIZE/SOLD_OUT也保存结果 | 不把随机队列说成先到先得秒杀，不把Redis说成库存事实源 |
| Outbox/Kafka | DB事务保证事件记录不与中奖结果分离，broker确认后标SENT，仍可能重复 | broker确认不等于业务消费成功；R1说明失败恢复仍有缺口 |
| Worker幂等 | eventId与drawId唯一约束，账本/marker同事务；消费者返回发生在业务提交后 | 不能称端到端exactly-once；默认错误处理器也影响可靠性 |
| 排队与租约 | MySQL任务是事实，Kafka提示可丢/可重投，扫描补偿；token阻止旧owner覆盖状态 | token不等于强制终止旧进程；抽奖幂等承担重复执行防护 |
| 审核/权限 | 身份来自服务端Actor，owner隔离、角色限制、状态迁移共同约束 | 有OPERATOR权限不代表可以绕过所有状态机，R2是例子 |
| 性能 | 分开报告受理延迟与终态延迟，保留拒绝/失败及账本对账 | 说明机器、单Broker、负载、时长、轮数；RPS不能直接等同并发人数 |
| 证据 | 区分单测、集成、故障注入、运行演示、目标设计 | 不将26项单测通过解释为上述缺口已复现或已修复 |

修复建议顺序：R1可靠消费 → R2审核路径 → R3并发重放，再决定是否补发奖状态投影。不新增业务模块，不全仓重构。

## 后续修复记录（同日）

本报告前文保留审查时的发现和证据边界。当前工作树已分别修改 Worker Kafka 错误处理、旧发布活动归属校验、抽奖拒绝后的同键复查，具体实现与验证状态见 `PROJECT_STATUS.md` 顶部。8 类 30 项单测与三类 34 项集成测试通过。Worker 集成测试经嵌入式 Kafka、隔离 MySQL 与注入的持久化异常，验证 12 次失败不提交 offset，恢复后唯一落账；这不是实际 MySQL 停机或进程硬崩溃演练。R3 新增拒绝分支有服务层交错测试，但尚无受控 MySQL 并发交错实证。面试时按此证据边界陈述。
