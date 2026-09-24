# Spec：D2-04 MySQL 活动持久化纵向切片

状态：规格假设、完整规格及实施计划已于2026-09-20获学习者批准；细化任务待学习者复审批准。批准任务前不修改生产代码或数据库。归属既有能力地图 `campaign-core`，是单一可独立验收能力，不新增能力地图模块。

## 1. 目标、使用者与成功定义

为D2-03的`Campaign`领域模型建立第一个真实持久化切片：应用层未来只依赖`CampaignRepository`端口，基础设施层使用MyBatis和XML把活动插入MySQL，并按业务id查询、重建为合法领域对象；Flyway负责从空库建立同一份Schema。

主要使用者是后续D2-05活动应用服务及当前学习者。成功不是“依赖能下载”或“H2测试通过”，而是：全新MySQL 8.4实例由Flyway迁移成功；DRAFT/PUBLISHED/CLOSED活动均能写入并按id读回；缺失id返回空结果；数据库约束拒绝明显非法数据；测试和文档能诚实说明事务、并发、HTTP及部署尚未覆盖。

本轮只包含活动持久化，不包含奖品表、Controller、REST API、修改/删除/分页、业务事务用例、缓存、并发扣库存或生产部署。

## 2. 已批准假设与关键决策

1. 使用MySQL 8.4 LTS和Testcontainers，不以H2模拟MySQL。
2. 集成测试需要Docker Engine；当前只读预检显示Engine未运行。执行阶段由学习者启动Docker Desktop，不重装CLI。
3. Java17、Spring Boot 3.5.16保持不变；MyBatis Starter使用兼容Boot 3.2～3.5的3.0.x，具体补丁版本在计划阶段以官方/Maven可解析版本锁定并留证，不采用要求Boot 4的4.0。
4. Flyway使用`flyway-core`和MySQL数据库模块`flyway-mysql`；MySQL Connector/J独立声明。Testcontainers依赖只进测试作用域。
5. 现有领域构造器要求`id>0`，本轮不偷偷引入数据库自增或修改D2-03公开契约。活动id由调用方提供，表主键不使用`AUTO_INCREMENT`；未来更换ID策略须单独更新规格。
6. 数据库读回不开放任意状态setter或公开状态构造器。持久化适配器先创建合法DRAFT，再按持久化状态执行零次或一次合法迁移：DRAFT原样、PUBLISHED调用`publish()`、CLOSED调用`close()`。未知状态必须失败，不能默认成草稿。
7. MySQL时间列采用`TIMESTAMP(6)`并统一UTC。MySQL只保存微秒精度；本轮往返测试使用微秒可表示的Instant，不宣称纳秒无损。是否增加领域精度约束留给后续专门决策。
8. `save`是仅插入语义；重复id必须失败且不得覆盖原行。本轮不把基础设施异常重新发明成尚未定义的业务错误码。

## 3. 数据模型与接口契约

### 3.1 `campaign`表

V1迁移从空库创建一张`campaign`表：

| 列 | MySQL类型 | 规则 |
|---|---|---|
| `id` | `BIGINT` | 主键；`id > 0`；非自增 |
| `name` | `VARCHAR(100)` | 非空；不自动trim；数据库至少拒绝空串，纯空白主要由领域层拒绝 |
| `starts_at` | `TIMESTAMP(6)` | 非空，UTC语义 |
| `ends_at` | `TIMESTAMP(6)` | 非空，且`starts_at < ends_at` |
| `status` | `VARCHAR(16)` | 非空，只允许`DRAFT/PUBLISHED/CLOSED` |
| `created_at` | `TIMESTAMP(6)` | 数据库生成，非空，仅审计写入时刻 |

表使用InnoDB和utf8mb4。迁移文件只前向创建，不写运行时`DROP TABLE`、示例账户或真实秘密。数据库约束是最后防线，不能替代领域校验；两层规则应一致但职责不同。

### 3.2 Repository端口

```java
public interface CampaignRepository {
    void save(Campaign campaign);
    Optional<Campaign> findById(long id);
}
```

端口位于领域侧包，只依赖`Campaign`和JDK `Optional`，不出现MyBatis、Spring JDBC、SQL或持久化行对象。`save(null)`和`findById(id<=0)`的参数边界由实现显式拒绝，不能形成难懂的SQL异常。

### 3.3 MyBatis基础设施适配

- `CampaignMapper`只暴露数据库行插入和按id选择，不充当领域Repository。
- `CampaignPersistenceRecord`承载SQL列映射；它是基础设施数据结构，不作为Controller DTO或领域对象。
- `CampaignMapper.xml`使用显式`resultMap`，列名与Java字段逐一列出；禁止依赖`SELECT *`或隐式驼峰猜测。
- `MyBatisCampaignRepository`完成领域对象↔持久化行转换，并实现`CampaignRepository`。
- 找不到id返回`Optional.empty()`；找到非法/未知持久化状态时明确失败，不能返回部分对象。

## 4. 技术栈、项目结构与文件边界

技术栈：Java17、Spring Boot 3.5.16、MyBatis Starter 3.0.x、Flyway、MySQL Connector/J、JUnit5、AssertJ、Spring Boot Test、Testcontainers MySQL。依赖版本优先由Spring Boot BOM管理；未被管理的MyBatis Starter在根POM属性/依赖管理中统一锁定，不散落多个版本。

预计代码/配置文件：

```text
backend/pom.xml                                           统一未管理的版本（仅确有需要）
backend/prizeflux-campaign/pom.xml                        Campaign数据库与测试依赖
src/main/resources/db/migration/V1__create_campaign_table.sql
src/main/java/.../domain/repository/CampaignRepository.java
src/main/java/.../infrastructure/persistence/CampaignPersistenceRecord.java
src/main/java/.../infrastructure/persistence/CampaignMapper.java
src/main/java/.../infrastructure/persistence/MyBatisCampaignRepository.java
src/main/resources/mapper/CampaignMapper.xml
src/test/java/.../infrastructure/persistence/CampaignRepositoryIntegrationTest.java
必要的测试专用配置文件                                隔离快速测试与真实数据库测试
```

这不是一个实现任务的文件数；计划阶段必须拆成每项最多约5个文件的顺序任务。若实现发现必须修改`Campaign.java`、Controller、Advice、CI或D2-03测试，先停止并更新规格征得批准。

## 5. 代码风格与映射示例

4空格缩进；包名表达分层；公开接口、迁移的重要约束和测试方法写中文业务意图。XML列和属性显式书写，SQL关键词大写，不把SQL拼进Java注解。

风格示例仅表达目标形状，不是已实现代码：

```xml
<resultMap id="campaignRowMap" type="...CampaignPersistenceRecord">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    <result property="startsAt" column="starts_at"/>
    <result property="endsAt" column="ends_at"/>
    <result property="status" column="status"/>
</resultMap>
```

不使用`SELECT *`，不在Mapper XML中复制领域状态迁移逻辑，不用Lombok隐藏本轮要学习的构造和映射行为。

## 6. 测试策略与证据分层

### 6.1 快速测试

原有Contract、MVC和纯领域测试应保留。引入数据库依赖后，非数据库测试不得因“没有本机MySQL”而意外失败；具体隔离方式在计划中确定并留回归证据，不能删除旧测试或把失败改成跳过。

### 6.2 真实MySQL集成测试

集成测试启动一次性MySQL 8.4容器，连接信息由测试运行时动态注入，不写入`.env`、源码或日志。Spring上下文启动后由Flyway运行V1，再经Repository/MyBatis访问真实表。

必须覆盖：

1. 空库启动后Flyway版本为1且`campaign`表、主键和关键约束存在。
2. DRAFT活动插入并按id读回，全部领域字段相等。
3. PUBLISHED和CLOSED分别往返，状态正确且不修改原对象。
4. 未知id返回`Optional.empty()`。
5. 重复id第二次插入失败，第一行未被覆盖。
6. 使用独立JDBC入口绕过领域对象写入至少一种非法数据时，数据库约束拒绝；证明数据库防线实际存在。
7. Unicode名称与微秒可表示时间往返；结束严格晚于开始。

测试不得使用H2、Mock Mapper、网络外部共享数据库或预先手工建表。容器无法启动属于环境失败，不冒称业务红灯；先用`docker info`区分CLI与Engine。

### 6.3 完整命令

以下为规格目标命令；计划批准后才执行实现验证：

```powershell
Set-Location -LiteralPath .
docker info

Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=CampaignRepositoryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate

git -C (git rev-parse --show-toplevel) diff --check
```

定向报告必须出现目标集成测试且tests>0；容器、Flyway、Repository往返和约束证据分别核对。`BUILD SUCCESS`不能单独替代测试类统计。全后端测试统计以当次Surefire XML为准，不预写数量。

## 7. 三层边界

### 始终执行

- 规格→计划→任务→实现四道审批；测试先行，编译红灯、行为红灯和环境失败分开记录。
- 使用真实MySQL测试，保护现有用户改动，检查秘密和差异范围。
- SQL、领域、Repository端口和MyBatis适配职责分离；学习者逐文件阅读并实际运行后才完成项目轮。
- 记录依赖来源、数据库版本、迁移结果、目标测试统计和证据边界。

### 必须先问

- 修改D2-03领域公开契约或状态规则；改用数据库自增/其他ID策略。
- 增加本规格外表、索引、操作或依赖；修改CI、Docker Compose、HTTP接口。
- 安装软件、启动/更改用户长期运行的数据库、提交、推送或部署。

### 禁止

- 用H2测试冒充MySQL集成证据；使用共享生产/个人数据库跑测试。
- 在仓库、SQL、日志或镜像写真实密码；依赖本机手工Schema而绕过Flyway。
- `SELECT *`、删除失败测试、用catch吞数据库异常、把本机成功写成远程CI/部署成功。
- 把本轮插入/查询扩大宣称为事务完整、并发安全、无超卖或完整CRUD。

## 8. 完成标准与学习验收

只有同时满足下列条件，D2-04才可完成：

- 依赖、迁移、端口、Mapper/XML、适配器和集成测试均通过批准后的任务清单实施。
- 用户启动Docker后亲自运行目标集成测试并回传目标统计；助手结果不能替代。
- 原有快速测试、全后端回归和六模块validate通过；无关用户改动保持。
- 用户能解释Flyway、Mapper代理、`resultMap`、Repository端口、数据库约束与领域校验的边界，以及为什么H2不能证明MySQL行为。
- 原答、订正、L层级和证据边界写入评分/错题；知识、项目、面试笔记关联D2-04并登记1/3/7/14复习。

通过只代表本轮创建/查询持久化切片的首轮闭环。事务传播、索引优化、隔离级别、并发锁、生产配置和长期记忆均需后续独立验证。

## 9. 开放项与规格复审结论

当前无必须由实现猜测的开放业务规则：调用方提供正id、插入而非upsert、缺失返回Optional、状态通过合法迁移重建、UTC微秒精度、真实MySQL测试均已写明。

计划阶段仍需用Maven实际解析确认兼容的MyBatis 3.0.x补丁版本和由Boot管理的Testcontainers/Flyway版本；解析失败是依赖计划问题，不能静默升级Spring Boot或改用H2。测试隔离方案也必须先用最小实验验证，再进入生产映射实现。

复审结论：目标是一个可垂直验证的活动持久化能力；数据库规则、Java接口、SQL映射、重建路径、时间精度、测试证据和不做范围闭合。预计文件多于5个，但可在计划中拆为依赖/迁移、端口/映射、适配/集成三组顺序任务，每项不超过约5个文件。完整规格批准后才进入计划阶段。
