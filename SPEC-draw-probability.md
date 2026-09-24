# Spec：D3-01 概率值对象与随机数端口

状态：学习者于 2026-09-23 明确批准本规格及“总和小于 1 的剩余区间表示未中奖”规则；归属已确认的能力模块 `campaign-core`。对应 `tasks/todo.md` 的 D3-01。按学习者同日要求，先完成全部 64 项构建，源码导读与分级学习验收随后进行。

## 目标与假设

为后续权重抽奖提供可校验、可测试的概率与随机数边界。生产抽奖使用本机伪随机源；单元测试注入固定随机值，使边界结果可重复。

本草案假设：沿用 Java 17、现有 Campaign 模块、`Prize.probability()` 的 `BigDecimal` 字段和 Contract 错误语义；不修改 D2-03 的 `Prize` 公开构造器。单项概率属于闭区间 `[0,1]`，各奖品概率之和允许小于 1；剩余区间表示“未中奖”，总和超过 1 拒绝。该总和规则会由 D3-02 策略再次验证，而非靠调用者自觉遵守。

## 契约与成功标准

- `Probability` 是不可变值对象，接收 `BigDecimal`，拒绝 `null`、负数和大于 1；允许 0、1 与不同小数位的等值写法。数值相等的 `1.0`、`1.00` 应按同一概率处理。
- 集合求和按十进制精确加法；空集合为 0，任一项为 `null` 或总和超过 1 时拒绝。不能先转 `double` 再求和。
- `RandomSource` 公开一个产生 `[0,1)` 值的方法。生产实现使用 JDK `ThreadLocalRandom`；测试实现接受固定序列，并在序列耗尽或值越界时明确失败，不悄悄回绕。
- 无随机种子、当前时间、数据库或网络依赖的测试能重复验证 0、接近 1、非法输入、总和边界和固定序列。
- D3-01 只建立概率和随机性基础；中奖选择、库存扣减、HTTP、统计公平性与密码学安全随机数属于后续任务。

## 技术栈与文件结构

生产代码位于 `backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/`：值对象在 `domain/model`，随机数接口在领域端口目录，生产适配器在 `infrastructure/random`。固定随机源和 JUnit 5/AssertJ 测试位于对应 `src/test/java`。沿用现有 Maven 依赖，不新增库或模块。

## 完整命令

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=ProbabilityTest,RandomSourceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate
git -C (git rev-parse --show-toplevel) diff --check
```

## 风格与测试策略

沿用 4 空格缩进、意图明确的命名和少量中文业务注释。测试先写失败场景，再实现最小契约。领域端口只依赖 JDK 与现有 Contract；测试不启动 Spring、Docker 或 MySQL。示例 API 风格：

```java
Probability probability = Probability.of(new BigDecimal("0.25"));
BigDecimal fraction = randomSource.nextUnitInterval();
```

定向测试、全后端测试及六模块校验分别保留实际执行结果；不能用编译通过冒充边界测试通过，也不能把伪随机统计样本当成绝对公平证明。

## 边界

- 始终：保留现有 `Prize`、领域规则和用户未提交改动；使用十进制计算并验证随机值范围；异常不泄露输入或内部细节。
- 先问：改变 `Prize` 对外构造/返回契约、改为总和必须等于 1、增加第三方随机库、引入数据库或 Spring 装配。
- 禁止：通过 `Math.random()` 静态调用绕开随机数端口、用 `double` 累加概率、把 D3-01 宣称为完整抽奖或防超卖实现。

## 已确认的业务选择

“未中奖”允许由小于 1 的剩余区间表达。若将来改为每次必出奖品，须另行调整总和规则及 D3-02 选择语义。
