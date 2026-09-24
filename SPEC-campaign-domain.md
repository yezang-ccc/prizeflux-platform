# Spec：D2-03 活动与奖品领域模型

状态：规格、计划、任务均获学习者批准，限定5文件实现/助手验证通过（定向50、全后端98测试，六模块validate）；用户阅读/运行和知识验收待完成，主任务未关闭。细化任务见tasks/D2-03-campaign-domain-tasks.md，证据见docs/learning/d2-03-campaign-domain-evidence.md。归属campaign-core，时间与质量口径以docs/interview/PLAN-execution-baseline-v2.md为准。

## 1. 目标、使用者与假设

为后续活动管理、抽奖与数据库映射提供合法的活动和奖品配置对象。对象自身守住业务规则，不能只依赖Controller的@Valid；HTTP之外的调用也不能创建非法配置。

本轮假设：只实现纯Java内存模型，无Spring注解、JSON约束、数据库、Mapper或HTTP接口。使用Java17现有工具和测试依赖，不增加第三方库。活动与奖品分开建模，不在活动中嵌入可变奖品列表。本轮库存字段是总数量配置，不是并发扣减后的实时剩余库存。

## 2. 术语与明确规则

### Campaign（活动）

字段：id(long)、name(String)、startsAt(Instant)、endsAt(Instant)、status(CampaignStatus)。id必须>0；name非null、非空白且长度<=100，不自动trim；两个时间非null且startsAt<endsAt。

创建后为DRAFT（草稿）。publish()返回PUBLISHED（已发布）的新活动对象，仅允许DRAFT发布；close()返回CLOSED（已关闭）的新对象，允许DRAFT或PUBLISHED关闭，CLOSED不能重复关闭或再次发布。不提供任意状态setter，不允许外部通过公开构造器指定状态。

发布可早于开始时间；PUBLISHED表示已发布，不等于此刻正在进行。isAvailableAt(Instant now)仅在状态PUBLISHED且startsAt<=now<endsAt时返回true，开始时刻包含、结束时刻不包含。草稿/关闭状态始终false。调用方显式传时间，不读系统时钟，测试不依赖sleep。now为null属于非法参数。

### Prize（奖品配置）

字段：id(long)、campaignId(long)、name(String)、totalStock(int)、probability(BigDecimal)。两个id必须>0；名称沿用非null/非空白/长度<=100；totalStock>=0，允许0；概率非null且数值在[0,1]，允许0和1。以BigDecimal.compareTo进行数值范围比较，不用double计算，也不要求小数位固定。

campaignId只表达归属，不验证对应活动在数据库中存在。单个概率合法不等于全活动概率总和合法；总和、Probability值对象及抽奖算法留给D3-01。本轮不实现剩余库存扣减，也不宣称防超卖。

### 不变量与对象形式

不变量就是对象必须始终满足的规则，例如开始时间早于结束时间。Campaign/Prize使用final普通类和private final字段，提供明确读取方法，无任意setter。状态迁移返回新对象，旧对象不变。id标识业务身份；本轮不实现实体equals/hashCode或把全字段比较等同于业务身份。

非法字段参数抛BusinessException(ErrorCode.INVALID_ARGUMENT)；非法状态迁移抛BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION)。不把原输入拼入对外错误消息。本轮领域单元测试检查异常类型及枚举，不直接检查HTTP状态；HTTP映射已有D2-02规则。

## 3. 项目结构与文件范围

生产/测试仅5份文件：

1. backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/domain/model/Campaign.java
2. backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/domain/model/CampaignStatus.java
3. backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/domain/model/Prize.java
4. backend/prizeflux-campaign/src/test/java/com/prizeflux/campaign/domain/model/CampaignTest.java
5. backend/prizeflux-campaign/src/test/java/com/prizeflux/campaign/domain/model/PrizeTest.java

规格/计划/任务及学习证据文档另行记录。不得顺手修改POM、现有Contract、DTO、Advice、健康接口或其测试；保留全部无关用户变更。后续持久化重建或配置修改另经对应规格审批。

## 4. 技术栈、风格与阅读协议

Java17；JUnit5与AssertJ沿用现有starter-test。领域类只使用JDK及已有Contract错误契约，不依赖MVC校验注解或启动Spring上下文。4空格缩进，方法名说明意图。

风格示例（参考，不是已经实现的代码）：

```java
/** 已发布且处于时间窗口内才可参与；结束时刻不包含。 */
public boolean isAvailableAt(Instant now) {
    // 实现阶段先验证now，再按本规格检查状态和时间。
    throw new UnsupportedOperationException("规格示例，未实现");
}
```

最终代码不得保留上述占位异常。每个公开方法及测试方法有中文用途注释，关键分支解释规则；测试辅助方法说明来源、参数和输出。导读每次只要求阅读一个短方法，并先解释陌生方法，不要求用户从长测试中凭空推断。注释解释业务意图而不是机械翻译每个分号。

## 5. 完整验证命令（实施审批后才执行新测试）

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=CampaignTest,PrizeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate
git -C (git rev-parse --show-toplevel) diff --check
```

定向报告必须出现两个目标测试类且tests>0，不能仅凭BUILD SUCCESS认定目标执行。新增数量按实际参数化用例报告记录，不提前虚构。全后端回归必须保留Contract28、健康1及D2-02的19用例；新增数量以实际报告为准。应用启动/HTTP、数据库及远程CI不属于本轮命令证明范围。

## 6. 测试策略与成功标准

纯Java单元测试，不使用@SpringBootTest/MockMvc；固定Instant输入，无真实网络、数据库或等待。先失败测试再最小实现；编译失败与行为断言失败分别留证。

| 规则 | 必测边界 |
| --- | --- |
| id与名称 | 0/负id、null/空串/纯空白名称；100字符通过、101拒绝 |
| 活动时间 | null时间、相等时间、反向时间拒绝；合法时间通过 |
| 生命周期 | 新建DRAFT；发布成功；草稿和已发布可关闭；重复发布、重复关闭、关闭后发布拒绝 |
| 可参与窗口 | 草稿/关闭false；发布后的开始前false、开始true、结束前true、结束false |
| 迁移后的对象 | 返回新状态对象；原活动字段和原状态不变 |
| 奖品数量 | -1拒绝，0/正数通过 |
| 单个概率 | null/负数/>1拒绝；0、1及正常小数通过；1.0与1.00范围判断均合法 |
| 异常语义 | 非法字段INVALID_ARGUMENT；非法状态迁移BUSINESS_RULE_VIOLATION |
| 时间与边界诚实性 | null now拒绝；不声称验证活动存在、概率总和或库存并发正确 |

助手实现、回归和范围复审通过后：用户逐方法阅读、实际运行目标用例并回传结果；绑定基础轮先确认熟悉度，已会则独立L3/L4变式，未教的Instant/BigDecimal等先补课。逐题给原答评分、订正、正确答案和层级。知识/错题/面试笔记及1/3/7/14复习留证才完成本轮限定闭环。独立L4未过、长期记忆未验不得宣称高阶全面掌握。按用户要求不主动推数组题。

## 7. 三层边界与明确不做

- 始终：遵循规格→计划→任务→实现四道审批，测试先行，保留用户变更，按方法中文讲解，知识掌握与代码通过分开记录。
- 先问：增加依赖/文件、改变状态规则、引入数据库/Controller、改变公开契约、安装skill、提交/推送/部署。
- 禁止：复制训练营源码、泄露secret、删失败断言冒充通过、无结果记CI或部署成功、把总库存模型写成防超卖证据。

## 8. 复审结论与技能提醒

规则已经覆盖输入、生命周期、时间和概率边界；5份代码/测试文件，可独立验证，不扩大为完整活动管理或抽奖。单概率验证与D3-01总和/值对象有明确分工。纯Java领域校验与D2-02的HTTP校验分开，不复用DTO充当领域对象。

已到原日历domain-modeling提醒节点，当前可用技能未包含它。核实mattpocock/skills仓库列出该候选，用于审查术语与边界；只提醒，不自动安装，也未声称已使用它。本轮主流程使用已读取的spec-driven-development；没有安装候选也可先审规格。

以上业务选项、实施计划及细化任务已分别获学习者批准。后续改变规则须先更新规格并征得确认；助手实现通过不等于用户知识已掌握。
