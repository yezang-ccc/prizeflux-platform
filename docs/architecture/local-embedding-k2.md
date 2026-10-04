# K2 本地中文 Embedding 决策与运行合同

2026-10-01。此文记录已实现的本地适配器，不表示真实 Qdrant 索引或 RAG 效果已验收。

## 选型

- 模型：BAAI `bge-small-zh-v1.5`，中文、512 维、24M 参数。原作者[模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)标记 MIT，并说明短查询检索可加中文 instruction，passage 不加。
- 执行格式：Qdrant 发布的[ONNX 转换包](https://huggingface.co/Qdrant/bge-small-zh-v1.5/tree/main)，仓库 commit `46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59`，约 95 MB；FastEmbed `0.8.1` 的[模型映射](https://github.com/qdrant/fastembed/blob/main/fastembed/text/onnx_embedding.py)声明该模型 512 维、CPU 可运行。FastEmbed Apache-2.0；模型包标记 MIT。模型文件只存在于 gitignored `.data/models/`，不随源码发布。
- 冻结模型身份：`BAAI/bge-small-zh-v1.5` + `qdrant-onnx-46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59-query-v1` + 512 维。查询加 `为这个句子生成表示以用于检索相关文章：`，文档不加；FastEmbed 做 CLS pooling、L2 归一化。改变模型、权重或输入规范必须改变 revision、重建版本化索引，旧 active 指针不被覆盖。

## 获取与校验

只有人工运行 `uv run --locked --extra local-embedding python -m prizeflux_agent.knowledge_cli prepare-local-model --confirm-download` 才下载固定 commit 的 7 个文件。下载先进入同一 `.data/models` 下的临时目录，逐文件验 hash 后才重命名为最终目录；中断/校验失败清理临时目录，不把半成品发布为可用权重。若最终目录已存在但 hash 错误，安全拒绝且不覆盖用户文件。运行时始终使用 `specific_model_path` 和 `local_files_only=True`，绝不按模型名隐式拉取。启动和离线 build 前逐个校验文件 SHA-256；缺失/不符则安全失败。权威校验表在 `embedding_local.py`，核心 ONNX 文件 `model_optimized.onnx` 的 SHA-256 为 `1294ea4b6331115a353d81f96b85e8c8d7fdcc284453d5b2fab5b016230aad38`，来源为[固定 commit 文件](https://huggingface.co/Qdrant/bge-small-zh-v1.5/blob/46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59/model_optimized.onnx)。其余 tokenizer/config 文件的哈希由固定 commit 的原文件计算并在 adapter 中冻结。

本机权重路径：`agent-service/.data/models/bge-small-zh-v1.5-46fbe35`。Agent 配置 `PFX_AGENT_EMBEDDING_PROVIDER=local-fastembed` 和 `PFX_AGENT_EMBEDDING_MODEL_PATH=<该目录的绝对路径>`；不能再填 HTTP URL、任意 model/revision/dimension 或 API key。HTTP 适配器保留，默认仍为 `none`；knowledge retrieval 默认仍关闭。容器镜像需显式以 `PFX_WITH_LOCAL_EMBEDDING=true` 构建，并将已校验权重只读挂载到容器指定绝对路径；K3 才配置隔离 Qdrant/卷/active index。

## 资源与失败边界

- CPU 推理线程为 2，单进程只允许 1 个进行中的 embedding，最多 32 条/批、每条 6000 字符；忙时安全失败，不排无界队列。同步 ONNX 推理在专用单线程 executor，取消异步等待也不会提前释放容量，直至底层推理结束。与远程 HTTP provider 一样，失败不会降级到 HashEmbedding 或无证据生成。
- 权重哈希验证发生在进程启动/构建时；本机首次验证与中文 query 推理返回 512 维、向量范数 1.0，冷启动+单次推理约 0.85 秒。只是单条烟测，不能宣称吞吐、召回或面试中的生产延迟。
- Python 3.14.6 Windows 主机上的 FastEmbed `0.8.1` 安装和推理已验证。可选容器镜像 `docker build --build-arg PFX_WITH_LOCAL_EMBEDDING=true` 的首次构建因 Docker 容器侧访问 PyPI `tls handshake eof` 失败；是环境网络阻碍，**Linux 容器运行尚未验证**。需要在网络可达后重建、挂载权重、测内存/CPU 和重启一致性。未执行真实 Qdrant build/在线查询，K2 容器关口与 K3/Q 未完成。
