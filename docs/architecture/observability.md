# PrizeFlux 运行观测（J1 首批）

## Campaign 指标出口

`local-full` 配置启用 `GET /actuator/prometheus`。Campaign 仍监听原端口 `8081`，没有新增 Compose 宿主机端口，也没有 Nginx 路由；产品 Compose 的应用网络为 internal。默认由现有 JWT 安全链保护，仅 `OPERATOR` 可读；可选监控 overlay 为 Prometheus 增加独立 Basic scraper 角色。没有 Prometheus/Grafana 服务时，Campaign 仍可启动，采集数据保存在进程内。

当前适合在隔离环境做运营诊断。可选 Compose 覆盖层 `deploy/compose.observability.yml` 为 Campaign 与 Worker 配置独立 Basic 抓取身份，按需通过一次性账号初始化容器创建 Performance Schema observer，并启动 Prometheus/Grafana；基础产品 Compose 不包含监控服务，也不受该覆盖层影响。Campaign/Worker 抓取密码、Grafana 管理员密码和 MySQL observer 密码由 `deploy/provision-observability-secrets.ps1` 在本机生成，文件位于 gitignored 的 `deploy/.observability-secrets/`，不会显示在命令输出中。Prometheus 同时加入 internal `product` 与 internal `observability` 网络；Grafana 只加入 internal `observability` 网络，无法直接访问业务容器。Grafana 的宿主机端口默认绑定 `127.0.0.1:3000`，不对局域网发布。

本机显式启用命令（从仓库根目录执行；需要先已配置 `deploy/.env.product`、Docker Engine 可用并生成上述秘密）：

```powershell
.\deploy\provision-observability-secrets.ps1
docker compose --env-file deploy/.env.product -f deploy/compose.product.yml -f deploy/compose.observability.yml --profile local-full --profile observability config --quiet
docker compose --env-file deploy/.env.product -f deploy/compose.product.yml -f deploy/compose.observability.yml --profile local-full --profile observability up -d --build campaign worker prometheus grafana
```

`config --quiet` 只验证合并后的 Compose 配置；上面的 `up` 仅请求构建/更新 Campaign、Worker 并启动本地监控服务及其依赖，不会由本次静态实现步骤自动执行。Grafana 地址默认为 `http://127.0.0.1:3000`，端口冲突时可在启动前设置 `PFX_GRAFANA_PORT`。管理员用户名默认为 `admin`，密码在本机秘密文件 `deploy/.observability-secrets/grafana-admin-password` 中。Prometheus 不发布宿主机端口。停用监控时不要删除命名卷；它们分别保留 7 日/1 GiB Prometheus 时序数据和 Grafana 状态。若未来清理这些卷，先核对其中数据并单独确认。

若需要隔离验收而不能复用本机产品数据，可在当前 PowerShell 会话为 `PFX_PRODUCT_MYSQL_VOLUME_NAME`、`PFX_PROMETHEUS_VOLUME_NAME`、`PFX_GRAFANA_VOLUME_NAME` 设置互不相同的新卷名，并使用新的 Compose `--project-name`；Compose 文件默认仍使用原有 `prizeflux_product_mysql_data`、`prizeflux_product_prometheus_data` 和 `prizeflux_product_grafana_data`，所以普通启动行为不变。隔离项目应使用单独端口，且验收后不要对默认产品项目执行 `down -v`。

告警规则由 Prometheus 评估并可在其 Targets/Alerts 页面查看；当前没有 Alertmanager，也不会发送邮件/IM 通知。该本机方案不是生产监控配置；`promtool check config` 与 `promtool check rules` 已用 Prometheus v3.5.0 校验通过，但 Prometheus/Grafana 服务运行态及告警触发尚未验收；生产环境还需另行做 TLS、凭据轮换、存储与告警投递设计。Docker 将 Grafana 映射限制到 loopback 是为了只允许本机访问；详见 [Docker Compose 端口映射文档](https://docs.docker.com/reference/compose-file/services/) 与 [Docker 网络文档](https://docs.docker.com/reference/compose-file/networks/)。

Actuator/Micrometer 提供 HTTP 状态与路由耗时、JVM 和 Hikari 连接池指标。业务指标名及 Prometheus 导出名例如：

| 指标 | 用途 |
|---|---|
| `http_server_requests_seconds_*` | HTTP 请求耗时与状态码；路由使用 Spring 匹配模板 |
| `hikaricp_connections_active` / `hikaricp_connections_pending` / `hikaricp_connections_timeout_total` | 连接池活动连接、等待与超时 |
| `prizeflux_draw_queue_submissions_total{outcome}` | 受理、幂等重放、限流、容量拒绝、保护不可用、异常 |
| `prizeflux_draw_queue_enqueue_seconds_*` / `prizeflux_draw_queue_status_query_seconds_*` | 受理与状态查询分开计时 |
| `prizeflux_draw_queue_processing_total{outcome}` / `prizeflux_draw_queue_process_seconds_*` | 完成、明确拒绝、重试、人工处理、重复终态、竞争/失租约 |
| `prizeflux_draw_queue_pending` / `prizeflux_draw_queue_oldest_age` | 非终态队列数量与最老任务年龄，单位分别为条、毫秒 |
| `prizeflux_outbox_publish_events_total{outcome}` / `prizeflux_outbox_publish_seconds_*` | Outbox Relay 已确认、重试、死信与租约失效 |
| `prizeflux_outbox_create_to_confirm_seconds_*` / `prizeflux_outbox_leases_reclaimed_total` | 创建至 Kafka 确认耗时与过期租约回收数 |
| `prizeflux_outbox_pending` / `prizeflux_outbox_leased` / `prizeflux_outbox_dead` | 最近一次采样时的待发送、租约中及死信条数 |
| `prizeflux_outbox_oldest_age_milliseconds` | PENDING/LEASED 中最老事件年龄；终态 SENT/DEAD 不拉高活跃积压年龄 |
| `prizeflux_draw_transactions_total{outcome}` / `prizeflux_draw_transaction_seconds_*{outcome}` | 新抽奖权威事务提交/回滚次数与事务总耗时（包含事务提交） |
| `prizeflux_draw_database_operations_total{segment,outcome}` / `prizeflux_draw_database_operation_seconds_*{segment,outcome}` | 抽奖事务内固定数据库操作段的调用数与耗时：配置锁定/加载、幂等写入、次数更新、库存扣减、结果、Outbox 和请求完成 |
| `prizeflux_mysql_innodb_lock_wait_relationships` | 每 10 秒采样的当前 InnoDB data-lock 阻塞关系行数；不是唯一事务数或累计等待时间 |
| `prizeflux_mysql_lock_wait_sample_last_success_epoch_seconds` / `prizeflux_mysql_lock_wait_sample_failures_total` | MySQL 锁等待观测最后成功采样时间和失败次数；失败时保留上次 gauge |

queue/outbox outcome 标签来自封闭枚举；不带 userId、campaignId、requestKey、queueRequestId 或 eventId。关联 ID 只允许出现在现有脱敏结构化日志，不进 Prometheus 标签。中奖业务结果仍以 MySQL 为准；Kafka 指标不能替代数据库队列积压。

队列与 Outbox backlog 分别由 `local-full` 内部 sampler 每 10 秒查询一次并缓存。抓取 endpoint 只读缓存，不在每次 scrape 时执行 COUNT/MIN SQL。采样失败保留上次 gauge 值，同时递增各自的 `*_pressure_sample_failures_total`，以 `*_pressure_last_success_epoch_seconds` 判断数据新鲜度；队列阈值不是严格并发硬上限。

抽奖事务计时仅覆盖进入 `TransactionTemplate` 的新业务尝试；事务外的幂等预读、已完成请求重放和抽奖队列受理不计入该事务指标。操作段分别包住应用层的一次 repository 调用，包含应用到 JDBC 的调用开销、数据库执行时间以及可能的连接/行锁等待。因而 `config_lock_and_load` 和条件库存更新可帮助定位慢段，但**不是 MySQL 原生锁等待时长**，不能据此断言等待都来自行锁；需要精确区分时，应结合 MySQL Performance Schema/事务锁视图采样。标签仅取固定枚举，不包含活动、用户或请求标识。遥测异常被隔离，不改变抽奖结果、库存、幂等或事务提交行为。

## J2 Kafka 等待分解（2026-09-30）

为诊断本机 50 RPS 排队场景，保持逐条发送、`acks=all`、`AckMode.RECORD` 和同步 offset commit 的正常业务协议，补齐以下观测。它们尚未给出瓶颈结论，也没有证明容量提升。

| 指标 | 语义与单位 |
|---|---|
| `prizeflux_kafka_publish_stage_seconds_count/sum{kind,stage,outcome}` | `kind` 只有 `draw_queue/fulfillment`；`stage=enqueue` 为 `KafkaTemplate.send()` 调用耗时，可能包含 metadata/buffer 阻塞；`stage=broker_ack_wait` 为返回 future 后同步等待确认的实际时间；`outcome=success/failure`。Timer 单位秒 |
| `kafka_consumer_coordinator_commit_latency_avg/max` | Boot 自动配置的 Micrometer consumer listener 导出每个 consumer client 的原生 commit 请求延迟，单位毫秒；它是 Kafka 滚动窗口统计，不是逐请求 tracing，也不是本轮端到端 P95 |
| `kafka_consumer_coordinator_commit_total/rate` | 每个 consumer client 的累计提交数/窗口速率；比较相邻快照时需要识别进程重启及 client 消失 |
| `kafka_consumer_coordinator_assigned_partitions` / `kafka_consumer_fetch_manager_records_lag_max` | 每个 client 的分区数和原生最大分区 lag；fetch lag 与 consumer group committed lag 的口径不同，不能相互替代 |
| `prizeflux_draw_queue_kafka_consumer_lag` | 配置的排队 topic/group 各分区 `latest − max(earliest, committed)` 之和，无 commit 时使用 earliest；负差取零，首次成功前为 -1。单位条 |
| `prizeflux_draw_queue_kafka_consumer_lag_last_success_epoch_seconds` / `prizeflux_draw_queue_kafka_consumer_lag_sample_failures_total` | 最后成功采样时间和失败数；失败保留旧值，不能把陈旧值理解为当前无积压 |

发送分段 Timer 不含通知 JSON 解析、MySQL `markSent` 或 Relay 调度等待。对比它与已有 `prizeflux_outbox_publish_seconds_*`、`prizeflux_outbox_create_to_confirm_seconds_*`，可以区分实际 future 等待、投递/回写和事件排队年龄；不同范围的时间不能简单当作同一个指标。Future 可能在开始等待前已经完成，ack-wait 此时很小，不能把它当作完整网络 RTT。观测异常被隔离，不将已确认投递转为重试；不把事件/活动/用户 ID 加入指标标签。

排队 lag sampler 仅在 `local-full` 且 Kafka、队列通知、队列消费者三个开关均启用时装配。默认每 10 秒触发一次（`prizeflux.metrics.draw-queue.kafka-lag-poll-ms`），独立 daemon 线程最多一个在途采样，未完成时跳过下一次，不积累后台任务；Admin 每项请求最多等待 5 秒。该隔离避免 broker 查询超时阻塞运行 Relay 的共享 scheduler。关闭时中断采样并关闭 Admin；scrape 本身只读缓存。单 topic/group 不含业务 ID 标签。

Kafka 发送等待中断现在保留线程 interrupt；Relay 处理当前事件的既定重试/死信回写后停止本批，未发送的已领取记录由租约到期恢复，不连续消耗其重试次数。超时但 broker 实际已收到的消息仍可能重复，既有消费者幂等/fencing 负责处理；本次没有改变确认策略。

隔离压测脚本 `Get-CampaignRuntimeMetricsSample` 保存所有 client series 的原始标签和值，以及 Outbox 总耗时/创建到确认、发送分段、排队 group lag。缺失 series 留空，`NaN/Inf` 留 `Value=null/IsFinite=false`，不补零；Kafka 采样状态需结合 last-success/failure 判断。原生 client 指标来自 Boot 的自动 consumer factory customizer，经本机真实 Embedded Kafka 和 Prometheus registry 验证了 commit 指标及发送 Timer 导出名；没有为此重复注册 Micrometer listener。

本批 5 个测试类共 16 项通过，含不依赖 Docker 的真实 Embedded Kafka：读取两条通知后、提交前 group lag=2，提交后 lag=0；真实 `KafkaEventPublisher` 确认数=2，commit latency/total 可导出。发送失败/超时/中断、观测故障隔离、采样缓存/失败/非阻塞、Relay 中断停止都有定向验证。Docker Engine 当前不可用，新的同条件到达率压测与 MySQL/Kafka 跨进程对账尚未运行。

## Worker 指标出口

Worker 的 `local-full` profile 将持久化履约重试调度器与模拟执行器一起启用。产品 Compose 需显式激活该 profile；默认 profile 仍不启动管理 HTTP，也不启用本地模拟履约。

Worker 不开放业务 HTTP：主服务端口设为关闭，仅单独管理端口 `8084` 提供 Prometheus scrape。Compose 不发布宿主机端口，Nginx 不配置转发，端点只在产品 Docker internal network 内可达。当前该只读 endpoint **没有认证**；仅适用于这个受信任的本地演示网络，不应接入不受信任容器或公网。生产部署须另行配置独立抓取身份、网络 ACL 与管理端口隔离。

| 指标 | 用途 |
|---|---|
| `prizeflux_worker_events_total{outcome}` / `prizeflux_worker_consume_seconds_*` | 已处理、重复、非法载荷、无效消息键及处理失败的数量/耗时 |
| `prizeflux_worker_fulfillment_executions_total{outcome}` / `prizeflux_worker_fulfillment_execution_seconds_*` | 履约成功、安排重试、转人工复核及 claim 失效的数量/耗时 |
| `prizeflux_worker_kafka_consumer_lag` | Worker 发奖 consumer group 对配置 topic 的总 lag（条） |
| `prizeflux_worker_kafka_consumer_lag_sample_failures_total` / `prizeflux_worker_kafka_consumer_lag_last_success_epoch_seconds` | Kafka offset 采样失败数与最后成功采样时间 |

Worker 标签值来自固定枚举；不会将 eventId、drawId、userId 或原始消息内容写入指标。遥测记录失败被隔离，不改变消息提交、MySQL 账本或重试决策。

Lag sampler 每 10 秒通过 Kafka Admin API 汇总 topic 分区 earliest/latest 与 group committed offset。Kafka 提交的是“下一个待读 offset”；没有已提交 offset 时，按此 Worker `auto.offset.reset=earliest` 配置以 earliest offset 作为起点。Scrape 只读缓存，不访问 broker；失败保留旧 lag 并增加 failure counter；首次成功前 lag 为 `-1`，可用 last-success gauge 判断数据新鲜度。该指标只覆盖配置的单一履约 topic/group，不能替代 Kafka 集群健康、分区副本、生产速率或业务 Outbox/MySQL backlog。

## MySQL InnoDB 当前锁等待观测合同

Campaign 的可选监控覆盖层会用独立的只读账号，仅查询 `performance_schema.data_lock_waits` 的 `COUNT(*)`。该表表达请求锁与阻塞该请求的持有锁之间的多对多关系；因此指标 `prizeflux_mysql_innodb_lock_wait_relationships` 是采样时的**阻塞关系行数**，不是唯一等待事务数，也不是等待时长。MySQL 说明该表可用普通的 Performance Schema 表级 `SELECT` 授权读取；实施时不授予全局 `PROCESS`，也不扩大 Campaign runtime account 权限。[MySQL `data_lock_waits` 表与权限说明](https://dev.mysql.com/doc/refman/8.0/en/performance-schema-data-lock-waits-table.html)

仅可选 `observability` Compose overlay 启用采样器和专用账号配置：MySQL root 凭据只供显式启动的一次性账号初始化容器使用，密码从本机只读 secret 文件读取；Campaign 使用另一个独立 secret 建立最大 1 连接的只读诊断池，JDBC URL 不指定默认 schema，避免观测账号需要业务库权限。账号对产品 schema 和其他 Performance Schema 表均无权限。未加载 overlay 时既不创建诊断池，也不查询 Performance Schema。

采样器每 10 秒执行一次轻量计数查询，Prometheus scrape 只读缓存 gauge。成功采样更新当前关系行数与 Unix 时间；失败增加脱敏 sample-failure counter，保留旧 gauge 和最后成功时间。告警只覆盖采样数据过期，不因任意非零等待直接告警；本地采样可能漏掉两次采样间已结束的短等待，不承诺观测所有瞬时锁等待。它用于区分“应用 repository 调用慢但采样时没有当前 InnoDB 等待”与“数据库此刻存在锁阻塞”，不是事务逐次 trace。

验收要求：单测验证缓存/失败语义；MySQL 8.4 Testcontainers 集成验证真实行锁阻塞出现时 gauge 大于 0，并证明监控用户可读 `data_lock_waits`、不能读业务 schema 或其他 Performance Schema 表；Compose 静态配置合并通过。测试不接触产品卷/数据库。后端定向命令（从 `backend` 目录执行）：`$env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=MySqlLockWaitMetricsTest,MySqlLockWaitMetricsIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。

## 本批未覆盖

2026-09-30补充：Relay通过应用层遥测端口导出 `prizeflux.outbox.database.stage` Timer，stage限定 `reclaim/claim/mark_sent/release_retry/mark_dead`，outcome仅 `success/failure`。reclaim与claim按每批调用计时；mark按每事件调用计时，不能把计数直接相加当事件数量。时间覆盖repository/JDBC调用，包括可能的连接、锁与提交等待，不是单条SQL或锁等待精确时间。返回0行/false属于调用success，是否确认SENT继续由原publish outcome表达。遥测异常不会改变原返回值或掩盖原数据库异常；不把event/user/lease作为标签。采样模块保存count/sum，缺失仍为null；新字段不会回填到旧JSON。

该分段因100ms/consumer8和10ms/consumer8的50 RPS×300秒长测均失败而新增；两轮性能数据来自分段新增前JAR，不能用新指标解释旧轮未记录的数据。接线定向验证不代表性能改善，下一轮须重新打包并先烟测。

- Prometheus/Grafana 运行态、告警触发及 Alertmanager 通知（本地配置/9 条规则已由 Prometheus v3.5.0 `promtool` 校验通过）。尚未启动监控服务或实际加载、触发告警。

规则草案以当前 `local-full` 默认值为本地起始阈值：队列最老年龄 300 秒来自默认准入保护值；Outbox 5 分钟和连接池等待时长用于本机演练，不构成生产 SLO。队列保护值可被环境变量覆盖，变更后需同步审查规则。不允许把用户运营 JWT 嵌入 Prometheus 配置。

这些未完成项见 `tasks/competitiveness-enhancement-tasks.md` 的 J1；本文件不代表生产监控或容量验收已完成。
