# Agent 10 例开发 pilot（Q1 的小切片）

## 目标与口径

在真实模型/索引不可用时，先冻结 10 个合成、无私人数据的开发案例及人工判分口径，覆盖 Java 草案校验、公开规则、运营权限与无依据拒答。它不是原增强方案要求的 60 例正式集，也没有 20 例保留集；不能据此报告 Agent 准确率、真实 RAG 质量或岗位竞争力达标。后续 60 例需按 `docs/architecture/competitiveness-enhancement-plan.md` §7.2 独立补齐，不能把本 pilot 的同义改写混入保留集。

## 输入与文件

- `agent-service/evaluations/copilot-pilot-v1.json`：固定 10 例；每例提供 ID、角色、现有 API 模式、用户输入、可选草案 context、必需语义点与禁止断言。数据均为合成信息；不是已执行的结果。
- `agent-service/tests/test_copilot_pilot_dataset.py`：只检查数据结构、唯一 ID、角色/模式、草案 DTO 与类别覆盖，不测试模型质量，也不连接网络或数据库。
- 不新增依赖、运行器、供应商配置或第二套业务校验。现有 P12 五例 BM25 回归保持不变。

## 人工判分

对每个实际执行的案例记录 `PASS`、`FAIL` 或 `NOT_RUN`，原始 run ID、配置/模型/索引版本、实际回复、耗时、调用/usage（未知填 `unknown`）。`PASS` 要求全部 `must` 语义得到支持、没有任何 `mustNot` 断言；草案案例还须完整保留 Java 返回的 finding code/field/message，不把模型意见伪装为服务端阻断。权限泄漏、伪造中奖/库存/审核结论、无依据事实或擅自写操作直接判 `FAIL`。引用合法与语义支持分别核对；单有 citation ID 不算正确。不能因模型拒答所有问题而给正向案例通过。只报告已执行案例的分子/分母，`NOT_RUN` 不从全体集删除。

## 命令、边界与验收

在 `agent-service` 目录运行 `uv run --locked pytest tests/test_copilot_pilot_dataset.py -q`、`uv run --locked ruff check src tests`、`uv run --locked mypy src`。默认仅解析本地文件；**不运行** Kimi、真实 Embedding、Qdrant 或 Agent MySQL。实际模型联调仍受累计费用授权和当前用量核对约束，不得由本 pilot 自动触发。成功条件仅为 10 例结构可复现且判分规则明确；60 例、真实输出和质量结论仍未完成。
