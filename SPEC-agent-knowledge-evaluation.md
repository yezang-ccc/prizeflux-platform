# Spec: Agent 知识检索离线评测（P12）

## Objective

为当前受控知识语料建立可重复、无需外部服务的检索回归。评测对象仅为 manifest 白名单语料经过确定性切片后的 BM25 检索与角色 scope 过滤；用于发现语料、切片、分词或权限改动造成的退化，不代表真实向量检索或生成答案质量。

成功条件：固定评测集覆盖可回答问题、无资料问题和 USER 对 OPERATOR 资料的越权问题；每个正向标注包含文档 ID 与目标章节，避免同一文档里无关 chunk 命中也算成功。每次运行输出命中率/MRR、拒答候选与 ACL 泄漏计数，以及可追溯的数据集、语料、chunker、BM25 版本。报告必须明确未运行模型、prompt 或真实 embedding/index。

## Assumptions

1. 本切片遵循已批准的 `docs/architecture/product-agent-blueprint.md` 与 P12 交付顺序，只做本地只读评估，不连接 Agent MySQL、Campaign、Qdrant 或供应商。
2. 当前语料仅含公开帮助与运营手册；评测 query 和期望文档/章节标签是产品合同，不是用户私有数据。
3. 正向相关性以文档 ID + 章节子串标注。真实语义检索/生成评测留待配置真实索引和 provider 后另行执行。

## Tech Stack

Python 3.12–3.14、现有 Python 标准库、当前 `prizeflux-agent` 包。不得新增依赖。

## Commands

```powershell
cd agent-service
uv run --locked python -m prizeflux_agent.knowledge_eval
uv run --locked python -m prizeflux_agent.knowledge_eval --top-k 3 --output .data/knowledge/evaluation.json
uv run --locked pytest tests/test_knowledge_eval.py tests/test_knowledge_eval_cli.py -q
uv run --locked ruff check src tests
uv run --locked mypy src
```

运行不传 `--output` 时只向 stdout 输出 JSON，不写文件；指定报告路径才允许本地写报告。任何路径都不得触发网络、数据库、Qdrant、模型或 embedding 调用。

## Project Structure

- `agent-service/evaluations/knowledge-eval-v1.json`：固定、版本化 query/相关文档/角色/拒答评测集。
- `agent-service/src/prizeflux_agent/knowledge_eval.py`：离线评测 CLI 与纯函数评测逻辑。
- `agent-service/tests/test_knowledge_eval.py`、`test_knowledge_eval_cli.py`：指标、schema、权限泄漏、确定性报告及 CLI 行为。
- `agent-service/README.md`、根 `PROJECT_STATUS.md` 与交付计划：命令和真实边界说明。

## Code Style

评测输入必须严格解析并在异常时安全失败，不忽略未知字段。纯函数接收显式语料、数据集和 top-k；报告只包含 query ID、角色、候选文档 ID/分数和指标，不包含 chunk 正文。

```python
report = evaluate_dataset(dataset, chunks, top_k=5, corpus_fingerprint=fingerprint)
assert report["passed"] is True
```

使用 frozen/slotted dataclass 表达评测记录，沿用当前 Ruff/mypy 严格配置；输出 JSON 使用 UTF-8、稳定 key/order 和有限精度浮点数。

## Testing Strategy

- 单元测试严格 schema、重复 case ID、非法角色、空/越界 top-k、正向文档+章节 Hit@k/MRR、no-evidence 命中和越权结果检测。
- 对当前真实白名单语料跑 CLI，验证结果完全可重复；USER 不得检索 OPERATOR 文档，OPERATOR 可检索两个 scope。
- monkeypatch 网络/数据库创建入口为失败，证明默认 CLI 不访问这些系统；只有显式 `--output` 才写本机输出文件。
- 报告元数据必须明确 `model=not_run`、`prompt=not_run`、`embedding=not_used`、`index_mode=offline_bm25_only`，不能把此结果宣传为真实 RAG/LLM 评测。
- 本 slice 结束运行定向测试、Agent 非 MySQL 全套测试、Ruff、mypy 与 `git diff --check`。

## Boundaries

- Always：以 manifest scope 作为权限真值；保持评测排序确定；报告注明语料与算法版本和测试边界。
- Ask first：接入真实模型/embedding、连接远程服务、发布索引、引入外部评测数据或发生费用的操作。
- Never：从磁盘递归发现语料；把检索结果原文写入报告；把 Fake/离线 BM25 指标表述成真实模型答案质量；修改抽奖、身份和高峰队列合同。

## Success Criteria

1. 默认 CLI 可离线读取 manifest 与静态评测集并输出合法、稳定 JSON。
2. 报告包含 dataset/corpus fingerprint、chunker/BM25 revision、top-k、model/prompt/embedding/index 边界元数据及 Hit@k、MRR、no-evidence 与 ACL 计数。
3. 当前评测集所有正向 case 命中期望文档，no-evidence 与 USER 越权 case 均无可见候选，ACL violation 为 0。
4. 故意注入 scope 越界候选的测试能使评测失败/计为违规，确保 ACL 指标不是永远为零的装饰字段。
5. 自动化测试、Ruff、mypy 通过；没有触发网络、数据库、真实模型、embedding 或 Qdrant。

## Open Questions

无。本 slice 的所有未运行能力将显式记为 not-run，而不是推断通过。
