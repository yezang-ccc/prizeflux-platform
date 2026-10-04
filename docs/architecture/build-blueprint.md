# PrizeFlux 全量构建架构基线

日期：2026-09-23。范围：Campaign、Worker 与 AI 运行单元的跨模块约定；具体实现范围按[聚焦版能力地图](../../CAPABILITY-MAP-FOCUSED.md)选择，实际完成状态仍以源码、运行和证据为准。

2026-09-26 更新：用户授权制定 Java + Python 双项目产品方案。AI 新实现、身份接入、运行存储、RAG、排队增强与迁移顺序以 [product-agent-blueprint.md](product-agent-blueprint.md) 和 [product-delivery-plan.md](product-delivery-plan.md) 为最新目标设计；本文件的 MySQL 权威事实、随机抽奖事务、授权与只读 AI 约束继续有效。下文 cloud-demo 合并 Java AI JAR 的装配方式仅保留为旧设计，Python 迁移后使用独立进程/容器。设计已写入，不表示对应代码已交付。

## 1. 运行单元与依赖方向

| 单元 | 职责 | 可依赖 | 不可依赖 |
|---|---|---|---|
| Contract | 稳定响应、错误码、版本化事件 DTO | JDK、必要的校验 API | Campaign 实体、数据库、Spring Web |
| Campaign | 活动、抽奖、库存、配额、中奖结果、业务 Outbox | Contract | Worker 的持久化实现、AI 模型 |
| Gateway | 公网入口路由、请求标识、边缘限流 | Contract、认证契约 | Campaign Mapper、业务写库 |
| Worker | 事件消费、发奖账本、重试与补偿 | Contract | Campaign 的库存表和事务 |
| AI | 知识检索、只读运营查询、SSE | Contract、受授权的 Campaign 查询 API | 抽奖写用例、数据库业务实体 |
| Web | 用户与运营演示 | 公开 HTTP 契约 | 内部数据库、内部事件 |

代码内依赖为 `controller → application → domain`，基础设施适配端口；事务入口在应用层。领域对象不引用 Spring MVC、MyBatis、Redis 或 Kafka。保留现有 `controller` 包，后续按能力建子包，不为命名统一搬迁 D2 文件。

本地 `local-full` 运行 Gateway、Campaign、Worker、AI 四个进程及 MySQL、Redis、Kafka。小规格 `cloud-demo` 增加明确的 `prizeflux-demo` 装配模块，将 Campaign、Worker、AI 作为普通 JAR 导入同一进程，Nginx 做入口。运行模块分别保留独立可执行包；Spring Boot repackage 输出带 classifier 的可执行包，普通 JAR 供 demo 依赖。组合模块显式导入三个功能配置和扫描范围，只激活轮询事件传输，不激活 Kafka 消费器。不能只靠 Profile 假定另一个模块的类已进入 classpath。

`cloud-demo` 只合并进程，不合并数据库所有权或业务事务。Campaign 和 Worker 各自管理表与 Flyway 历史，可共用一台 MySQL 实例的不同 schema。AI 使用独立有限线程池；模型慢或不可用不能阻塞抽奖。2C2G 的堆、堆外内存、线程、MySQL、Redis 和系统开销在真实部署前测量，不预设一定装得下。

## 2. 核心抽奖契约

活动草稿可以设置奖品及库存；发布时在同一数据库事务中确认配置完整、概率合法，并冻结 `configVersion`。已发布版本不可原地修改。现有 V1 `campaign` 迁移保持不变，新增能力使用后续迁移。

`Probability` 继续接受任意合法十进制 `[0,1]` 值；在发布边界只接受最多六位小数的配置，尾随零可去掉。以 1,000,000 个等概率整数票号构造冻结分布，不静默舍入。总和可以小于 1，余量是未中奖；总和大于 1 拒绝。线性和 Alias 策略共享相同的候选排序、票号空间、未中奖与库存耗尽语义，统计分布应相同，逐次随机序列无需相同。

先从冻结分布选候选，再条件扣库存。选中已售罄奖品返回可重放的“库存耗尽/未获得奖品”结果，不自动重抽；要重抽需重新定义概率合同。零概率奖品不会被选中。候选排序固定为奖品 ID 升序，防止输入集合迭代顺序改变映射。`RandomSource` 可继续返回 `[0,1)` 的 `BigDecimal`；策略转换票号时明确边界规则，不能将概率先转 double 累加。

每次用户抽奖请求包含客户端生成的随机 `requestKey`，并绑定服务端认证的 `userId`。幂等唯一键为 `(operation, userId, requestKey)`；规范化请求指纹包含活动 ID 和影响结果的参数。同键同指纹读取原结果，同键异指纹返回冲突。结果包括未中奖、库存耗尽和中奖；重放不重新随机、不再扣参与次数与库存。业务次数由 MySQL 事务负责；Redis 用户/IP 限流仅负责抗滥用。

新请求的一个本地事务执行：占有幂等记录 → 权威读取活动状态/时间/配置 → 条件消耗业务次数 → 选择候选 → 条件扣库存 → 写结果 → 中奖时写 Outbox → 提交。数据库唯一键冲突在失败事务外读取赢家结果；提交结果未知时以同一请求键查询，不能直接新抽或退款。死锁仅对有界、无外部副作用的事务重试。活动关闭后新进入权威状态检查的请求拒绝；已经通过检查的在途请求可完成。时间使用注入的 `Clock`，存储 UTC；每日限次另设业务时区。

建议 Campaign 数据责任：`campaign`、`campaign_config_version`、`prize_config`、`prize_inventory`、`draw_request`、`draw_result`、`user_campaign_quota`、`outbox_event`。`draw_request` 唯一键保护业务重放；`draw_result` 按请求唯一；库存 `remaining >= 0`，扣减语句包含 `remaining > 0`。索引由实际查询模式确定，历史分页使用稳定游标。

## 3. Redis 与事件失败协议

Campaign 的展示读可以缓存，抽奖资格、关闭状态、业务次数和库存最终提交走 MySQL。缓存失效配合 TTL、版本号与失败重试，承认短暂展示陈旧；通用 `CachedCampaignRepository` 不包装所有权威读取。

Redis 奖品池预占携带 `requestId`、`poolGeneration`、`reservationToken`。Lua 对同一代次原子预占；MySQL 条件更新仍为最终防超卖线。MySQL 成功则标记预占完成；明确失败时，补偿先查数据库请求终态再幂等释放；提交结果未知时延后核验。旧代次预占不得释放到新代次。池重建从数据库余量和未决预占推导可用量，并协调在途请求；不直接用初始总库存覆盖线上数值。Redis 不可用时缓存可回源，抗滥用限流按容量选择受限降级或拒绝，不能无界放量。

Outbox 在中奖事务内写入。Relay 用租约及领取标识取得待发事件，事务外发布，broker 确认后用领取标识更新；失败时按 `available_at` 做持久化指数退避（默认2秒起、30秒封顶），并由最大尝试次数限制后进入 `DEAD` 处理。超时租约可重新领取。事件可能重复，契约采用 `eventId`、`drawId`、`schemaVersion` 和最小必要载荷。Worker 对 `eventId` 持久去重，同时以 `drawId` 保护相同中奖结果不重复发奖；处理记录和内部虚拟权益账本在一个 Worker 事务内提交，提交后才确认消费。真实外部供应商需使用稳定 `fulfillmentId` 的幂等接口或结果查询，不能只靠本地去重表保证一次性效果。

排队抽奖的 `DrawQueued` Kafka 事件只作为唤醒提示；排队请求、租约、幂等和开奖结果以 Campaign MySQL 为事实来源，丢失通知由有界扫描器恢复。队列消费者的初次 offset 策略必须显式使用 `earliest`，避免新消费组在已有积压消息时从 `latest` 跳过；重复投递仍由 MySQL claim token/fencing 和抽奖事务幂等处理。Kafka 失败恢复的单消费者演练不代表多消费者 rebalance 或容量验收。

Worker 的结果事件回流 Campaign，幂等更新发奖状态投影，前端按权限查询。状态至少区分 `PENDING`、`PROCESSING`、`SUCCEEDED`、`RETRYING`、`MANUAL_REVIEW`；转换规则由 D7-01 落码。`local-full` 走 Kafka，`cloud-demo` 走租约轮询，但两者调用同一消费用例且一次只启用一种传输。慢下游的超时、重试、熔断、隔离放在 Worker，Campaign 抽奖事务不调用外部发奖。

## 4. 身份、HTTP 与页面契约

第一版单租户，登录角色为 `USER` 和 `OPERATOR`。普通 USER 可同时参与抽奖、拥有自己的活动草稿；不另加可自选的 CREATOR 角色。USER 活动通过 owner_user_id 限制读写，提审后由 OPERATOR 审核，通过才公开；运营员拥有跨用户审核/管理权限。D4 定义 `Actor` 端口，D8 接入真实账号与认证；外部请求不能通过 `userId` 参数冒充身份。Gateway 校验不替代 Campaign 和 AI 的授权；个人记录、活动归属、运营写操作和 Agent 工具都检查资源归属或角色。若后来增加多租户，账号、唯一键、查询、缓存、事件、文档 ACL 全链路增加 tenant 维度。

当前四个活动接口保持：`POST /api/campaigns`、`GET /api/campaigns/{id}`、`POST /api/campaigns/{id}/publish`、`POST /api/campaigns/{id}/close`。后续最小页面接口清单及归属如下，路径是实现目标，具体请求/响应 DTO 在对应任务锁定：

| 功能 | 目标路径 | 归属与先决条件 |
|---|---|---|
| 注册/登录/当前用户 | `POST /api/auth/register`（仅 USER，IP Redis 限流）、`POST /api/auth/login`、`GET /api/auth/me` | D8 认证；运营账号仅受控初始化 |
| 运营活动列表、草稿详情 | `GET /api/campaigns`、`GET /api/campaigns/{id}` | D12 页面前补 Campaign 查询 |
| 草稿奖品配置/列表 | `PUT /api/campaigns/{id}/prizes`、`GET /api/campaigns/{id}/prizes` | D4 发布冻结前完成 |
| 用户可参与活动 | `GET /api/available-campaigns` | D12 页面前补受控查询 |
| 抽奖与同请求查询 | `POST /api/campaigns/{id}/draws`、`GET /api/draw-requests/{requestKey}` | D4 事务/幂等 |
| 个人历史、发奖状态 | `GET /api/me/draws`、`GET /api/me/draws/{id}` | D7 结果回流、D9 历史索引 |
| 只读运营统计 | `GET /api/ops/summary` | D11 工具与运营页共用 |
| 短链创建/跳转 | `POST /api/short-links`、`GET /r/{code}` | D9 安全目标校验 |
| AI 流式问答 | `POST /api/ai/answers/stream` | D11 鉴权、SSE |

现有创建活动使用客户端传入 long ID；改为服务端分配 ID 时应保留兼容策略，统一处理 JavaScript 超过安全整数范围的标识，公开 DTO 可采用十进制字符串。统一错误目前 `data=null`；字段级校验消息若要进入页面，增加明确的安全错误字段，不让前端猜测内部异常格式。

USER 创建者与审核切片的实际接口已在对应规格中落定：`POST/GET /api/me/campaigns`、`GET/PUT /api/me/campaigns/{id}`、`GET/PUT /api/me/campaigns/{id}/configuration`、`POST /api/me/campaigns/{id}/submit`、只读 `POST /api/me/campaigns/validate`；运营审核使用 `/api/ops/reviews/campaigns` 与 `/api/ops/reviews/campaigns/{id}/approve|reject`。owner 从服务端 Actor 取值，review 与配置读取按 OPERATOR 授权。具体状态及验收以 `SPEC-campaign-ownership.md`、`SPEC-campaign-review.md` 为准。

短链只允许批准的目标协议和域名/路径，不允许任意内网目标。浏览器 AI 流采用可带认证头的流式请求方式，支持取消、超时、心跳、并发上限和最大事件体积；断线不自动重复付费调用。模型文本与检索片段按不可信内容渲染。

## 5. AI 数据与安全边界

AI 的 Fake Model 用于离线流程测试，真实模型与真实 Embedding 分别通过端口配置。知识导入保留文档来源、版本、切片 ID、访问级别和向量版本；本地内存索引明确重启重建，演示或正式环境选择可恢复存储。检索先按当前认证用户可见范围过滤，再把片段传给模型。无相关资料与模型不可用返回不同结果。

工具只暴露固定只读操作和参数 schema；服务端根据身份决定可见数据，不接受模型返回的角色或用户 ID 作为权限依据。调用次数、超时、结果长度和成本有上限。提示注入检测只辅助记录，真正执行限制由代码中的工具白名单、授权与参数验证完成。评测分别记录 Fake 流程、真实检索、真实生成和工具调用的质量、耗时与成本。

## 6. 交付顺序与集中验证

以下 D 编号安排仅作为早期设计历史参考；旧版 64 项清单已按用户要求退休，不再构成交付范围。当前范围与顺序以 [`CAPABILITY-MAP-FOCUSED.md`](../../CAPABILITY-MAP-FOCUSED.md)、`tasks/plan.md` 顶部及 [产品交付计划](product-delivery-plan.md) 为准。保留本节安全、事务、验证与学习者验收等契约，不据此恢复旧任务清单。

1. **架构与本地可运行底座**：本基线、真实数据库配置和最小 Compose、Linux 容器 CI、打包结构；D3 算法与 D4 数据模型可并行设计。
2. **确定性业务闭环**：D3 策略、D4 奖品配置/抽奖事务/幂等、D9 历史查询。完成后一次性运行纯 Java、MySQL 集成、真实 HTTP 冒烟与并发不变量。
3. **高并发与发奖闭环**：D5/D6 Redis 协议、D7 Outbox/Worker/结果回流、D8 身份与入口。完成后运行 Redis/Kafka 故障与重复投递矩阵。
4. **演示产品**：D9 短链、D10/D11 AI、D12 Vue、cloud-demo 装配。完成后前后端端到端与打包冒烟。
5. **生产证据与学习**：D13 性能/观测/恢复，D14 真实云端/文档/简历；学习者本人答辩与评分单独记录，不能由代码测试代替。

每批开发期间执行必要的编译或小范围测试定位错误，批次结束才跑完整后端、前端和环境测试；不为每个 D 编号重复全量回归。测试失败记录实际环境和原因，不用“差不多”替代关键数据不变量。性能报告区分抽奖受理延迟与发奖完成延迟，保留原始结果和机器配置。公网部署、真实模型调用及学习者实际答辩需要对应真实环境与参与，不能预填完成。

## 7. 当前状态

本节契约持续有效；代码完成度与最近一次验证以仓库根目录 [`PROJECT_STATUS.md`](../../PROJECT_STATUS.md) 为准，避免在架构基线里复制易过期的测试数字。除核心抽奖、身份/限流、Outbox 与 Worker 外，当前演示产品还包括 Vue 3 参与/运营界面、USER 自有活动草稿、owner 隔离、运营审核后发布，以及使用 Fake Model 的只读 Creator Harness 校验。公网部署、真实模型与真实供应商履约仍不在已交付范围。
