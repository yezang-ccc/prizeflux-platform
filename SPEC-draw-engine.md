# D3-02/D3-03 抽奖策略规格

状态：依据已确认的 64 项构建范围和 [全量架构基线](docs/architecture/build-blueprint.md)进入实施；D3-01 概率值对象及随机端口已完成。此规格只负责从冻结概率分布选出奖品 ID 或未中奖，不查询活动、不扣库存、不发奖。

## 目标与契约

- `DrawCandidate` 保存正数奖品 ID 和合法 `Probability`。
- 构建策略时复制输入，按奖品 ID 升序冻结顺序；拒绝空指针、重复 ID、概率和超过 1、有效小数超过六位。允许空奖品列表和零概率候选，余量为未中奖。
- 概率通过精确十进制转换为 1,000,000 个整数票号；不四舍五入。`RandomSource` 的 `[0,1)` 值乘以 1,000,000 后向下取整为票号，越界/空值明确失败。
- 两个实现共享相同票号分布。线性策略每次顺序比较，预计算策略构造不可变票号表后 O(1) 查找。预计算表每个配置约 4MB，需由后续应用层限制缓存数量，不在抽奖请求中逐次构造。
- 返回 `OptionalLong.empty()` 表示未中奖。策略不判库存；库存耗尽后不重抽由 D4 抽奖用例落实。
- 测试覆盖输入顺序、零概率、空列表、边界票号、余量和两种策略一致性。有限样本频率只作统计检查，不宣称数学证明公平。

## 文件与验证

源码位于 `backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/domain/draw`：`DrawCandidate`、`DrawStrategy`、`DrawDistribution`、`LinearWeightDrawStrategy`、`PrecomputedTicketDrawStrategy`。测试位于对应 `src/test/java`；不增加 Maven 依赖、HTTP、数据库或 Spring Bean。完整后端测试在本批核心实现结束后集中运行，不逐任务重复。

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=DrawStrategyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
```

现有 `Prize` 构造与 `Probability` 范围合同保持原样；六位小数是冻结可抽奖配置的额外规则。库存、参与次数、幂等、异常 HTTP 映射和真实发奖属于后续 D4/D7。
