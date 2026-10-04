# 本地容器版全栈（P14-E）

这是本地演示/联调栈，不是公网生产配置。Campaign、draw-executor、Worker、legacy Java AI、Python Agent、MySQL、Redis 与 Kafka 只加入内部 Docker 网络；宿主机只发布绑定 `127.0.0.1` 的 Vue/Nginx 入口。Agent 默认 Fake Model，Worker 履约明确使用 simulated 模式；不配置真实供应商，不接触现有 `deploy` foundation 卷或旧 3306 服务。

## 首次启动

在仓库根目录的 PowerShell 执行：

```powershell
.\deploy\new-product-env.ps1
docker compose --project-name prizeflux --env-file deploy/.env.product --file deploy/compose.product.yml --profile local-full config --quiet
docker compose --project-name prizeflux --env-file deploy/.env.product --file deploy/compose.product.yml --profile local-full up --build --detach
```

环境文件由本地脚本新建，包含互相隔离的 64 位随机十六进制数据库密码和 JWT 密钥；脚本不会覆盖已有文件，也不把密钥打印到终端。不要把 `.env.product`、`docker inspect` 输出或完整配置粘贴到聊天/日志。Compose 配置检查只验证展开语法，不会启动服务；`up` 首次构建/拉取镜像需要 Docker Registry 和 Maven/npm 仓库网络。

若 Windows 宿主已缓存 Maven 依赖、但 Docker 构建阶段访问 Maven Central 因 TLS/代理失败，可先在宿主构建**当前源码**，再用只复制已打包 JAR 的本地 fallback 镜像；不要直接沿用旧镜像声称验证了新代码：

```powershell
$env:JAVA_HOME = 'D:\java\jdk-17' # 改为本机实际 JDK 17 路径
Push-Location backend
.\mvnw.cmd -q test
.\mvnw.cmd -q -DskipTests package
Pop-Location

foreach ($module in @('prizeflux-campaign', 'prizeflux-worker', 'prizeflux-ai')) {
    $image = if ($module -eq 'prizeflux-ai') { 'prizeflux-legacy-ai' } else { $module }
    docker build --pull=false --build-arg "MODULE=$module" `
        --file backend/Dockerfile.local-artifact --tag "${image}:latest" `
        "backend/$module/target"
}
docker tag prizeflux-campaign:latest prizeflux-draw-executor:latest
docker tag prizeflux-campaign:latest prizeflux-campaign-bootstrap:latest
docker compose --project-name prizeflux --env-file deploy/.env.product `
    --file deploy/compose.product.yml --profile local-full build --pull=false proxy

# 本机演示明确关闭付费模型；只影响当前 PowerShell 进程及由其创建的容器。
$env:PFX_AGENT_MODEL_PROVIDER = 'fake'
$env:PFX_AGENT_MODEL_API_KEY = ''
docker compose --project-name prizeflux --env-file deploy/.env.product `
    --file deploy/compose.product.yml --profile local-full up --no-build --detach
```

此路径仍需要已存在且与源码匹配的 `agent`/`agent-migrate` 正式镜像；缺少时必须先按正常 Dockerfile 成功构建，不能把本地 Python 测试当作容器镜像。不要使用 `--remove-orphans` 或 `down --volumes` 清理知识栈/业务数据。

启动后打开 `http://127.0.0.1:8080`。可用本地注册创建 USER。若需要 OPERATOR 审核权限，在站点就绪后运行一次一次性初始化脚本：

```powershell
.\deploy\bootstrap-operator.ps1
```

脚本会安全提示输入运营用户名和密码；密码不会写入 `.env.product`，也不会输出。已存在同名账号时程序会拒绝覆盖；正常 `local-full` 启动不启用 bootstrap profile。结束时运行：

```powershell
docker compose --project-name prizeflux --env-file deploy/.env.product --file deploy/compose.product.yml --profile local-full down
```

该命令保留本配置独有的 `prizeflux_product_mysql_data` 数据卷；不要添加 `--volumes` / `-v`。本栈不映射 MySQL、Redis、Kafka 或后端端口到宿主机。只保留到桌面、停止或重建本栈不会清除业务数据。数据库账号变更需单独做迁移，不可只改 `.env.product` 后期待已初始化的 MySQL 自动轮换密码。

## MySQL 逻辑备份

产品栈运行期间，可对三个业务 schema 做一致性逻辑快照。先选择一个不在仓库内、且所在磁盘有访问保护的目录：

```powershell
$backupDirectory = Join-Path $env:LOCALAPPDATA 'PrizeFlux\backups'
$backupPath = Join-Path $backupDirectory ("prizeflux-{0}.sql" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
.\deploy\backup-product-mysql.ps1 -OutputPath $backupPath
```

脚本只接受健康的 `prizeflux` Compose 项目 MySQL 服务，并再次核对它挂载的是精确的 `prizeflux_product_mysql_data` 命名卷；同时确认三个 schema 存在且业务表均为 InnoDB。它使用 `mysqldump --single-transaction --quick`，不会主动停止业务进程或锁住 InnoDB 表；备份窗口内不要运行 Flyway/Alembic 或其他 DDL 变更。成功后只输出文件路径、大小与 SHA-256，不输出凭据或 SQL 内容；已有目标文件不会被覆盖，临时失败文件会清理。

导出为**未加密的明文 SQL**，内容包含业务数据与用户凭证哈希。不要将其放进仓库、提交、粘贴到聊天或放入未受保护的共享目录；按你设备的磁盘加密与备份策略保护它。当前实现是手动本地逻辑备份，不包含自动排程、加密、异地副本或时间点恢复（binlog/PITR）。

可用 `scripts/smoke-backup-product-mysql.ps1` 在随机命名、无网络的临时 MySQL 源卷上复验导出，再恢复到另一个全新临时卷并核对样例数据。该演练不会触碰产品卷；真实产品数据恢复仍必须先恢复到新的隔离卷/栈并完成业务对账，不能把 dump 直接导入正在使用的产品库，也不要对原产品卷执行删除或覆盖。

## 运行与权限边界

- MySQL 初次初始化创建 `prizeflux`、`prizeflux_worker`、`prizeflux_agent` 三个 schema，以及各自独立 runtime / migration 账号。Campaign 和 Worker 仍由各自 Spring Boot Flyway 配置使用专属 DDL 账号迁移；运行 datasource 仅有 schema 内 DML。Agent 通过 `agent-migrate` 一次性 Alembic job 使用 Agent migration 账号，API 容器只拿 Agent runtime 账号。
- `draw-executor` 使用 Campaign 同一事务实现、共享 Campaign schema/runtime 账号、禁止 Flyway 并行迁移且不开放 HTTP 端口；Campaign 负责唯一 schema migration。Kafka 与 Worker 为单节点本机配置，发奖是模拟履约，不代表真实供应商交付或高可用。
- 唯一入口是同源 Nginx：`/api/` → Campaign，`/ai-api/` → 暂保留的 Java AI，`/agent-api/` → Python Agent。Agent run 事件路由关闭代理缓冲、延长读取超时以支持 SSE。Campaign 内部 HTTP 例外只信任 `http://campaign:8081` 这一精确 origin，并且只在 `local` 环境；模型、Embedding、Qdrant 不继承该例外。
- Docker 网络设置为 internal；目前 Fake 默认、不需要外连模型。要启用真实模型/Embedding、启用公网访问/TLS 或部署公开演示，必须另行完成供应商费用、网络出口、密钥、TLS、固定镜像 digest 和安全复核，不能直接把这个文件改成公网监听。

## 可选：本机知识索引（K3，运行态待验）

`compose.agent-knowledge.yml` 是本地演示用叠加文件：固定 [Qdrant `v1.19.1`](https://github.com/qdrant/qdrant/releases/tag/v1.19.1)，只加入内部 `product` 网络，不发布宿主端口；Qdrant/BM25 使用两个独立命名卷，不复用产品 MySQL 卷。Qdrant 在这个隔离网络中无 API key，**不能**直接用于公网、共享多租户网络或生产。Agent 镜像增加可选 FastEmbed 依赖，把已校验的本机权重目录只读挂载到 `/models/bge-small-zh-v1.5`；默认 `PFX_AGENT_KNOWLEDGE_RETRIEVAL_ENABLED=false`。本机模型来源、7 文件 hash 与版权见 [`K2 决策`](../docs/architecture/local-embedding-k2.md)。2026-10-02 正式 Agent/迁移镜像已构建并运行，原 14 chunks active 索引由正式镜像核验；故障保持旧指针与一次性 Fake/检索开启应用的权限查询通过。带鉴权 HTTP Run/浏览器显示仍待验，详见 `PROJECT_STATUS.md` 顶部。

先确认 `agent-service/.data/models/bge-small-zh-v1.5-46fbe35` 已存在并通过 `prepare-local-model` 复验，再在仓库根目录执行：

```powershell
$knowledgeCompose = @('--project-name', 'prizeflux', '--env-file', 'deploy/.env.product', '--file', 'deploy/compose.product.yml', '--file', 'deploy/compose.agent-knowledge.yml', '--profile', 'local-full')
docker compose @knowledgeCompose config --quiet
docker compose @knowledgeCompose up --build --detach qdrant agent
docker compose @knowledgeCompose exec -T agent python -m prizeflux_agent.knowledge_cli build --confirm-external-calls-and-writes
docker compose @knowledgeCompose exec -T agent python -m prizeflux_agent.knowledge_cli verify-active
```

构建命令会读取白名单语料、在 Agent 自有 MySQL 中登记新索引并向 Qdrant 写新 collection；不是只读操作。只在确认连接的是本机 `prizeflux` 项目、模型文件已验证且允许写知识库后执行。build 失败时保留旧 active 指针已有单元与本地容器 Embedding 故障注入证据；进程强杀窗口未验证。成功后先重启 `qdrant`/`agent` 并重复 `verify-active`，再以 USER/OPERATOR 分别做授权查询和来源核对，不能只凭点数宣称 RAG 质量。`verify-active` 不调用模型、不写索引，只核对 active 记录、BM25 校验和版本/条数以及 Qdrant 点数。

确认真实索引与角色隔离后，才在忽略的 `deploy/.env.product` 中设置 `PFX_AGENT_KNOWLEDGE_RETRIEVAL_ENABLED=true` 并仅重建 Agent。关闭时改回 `false` 并重建。运行栈结束用常规 `down`，**不要**加 `--volumes` / `-v`；Qdrant、BM25 和产品 MySQL 卷都应保留。Qdrant 镜像目前仅固定版本标签，尚未锁 digest，也没有公开部署审查。

## 可选：启用真实模型

当前默认仍是 Fake Model，打开 AI 助手不会产生模型供应商费用。Python Agent 已支持 OpenAI Chat Completions 兼容协议；真正切换前，先选定供应商、确认模型名称和计费方式，并给本次联调设定可接受的费用/调用上限。不要把 API Key 发到聊天或写进 Git 跟踪文件。

确认后，在本机被 Git 忽略的 `deploy/.env.product` 中填写以下变量：

```dotenv
PFX_AGENT_MODEL_PROVIDER=openai-compatible
PFX_AGENT_MODEL_BASE_URL=<供应商的 OpenAI 兼容 API 根地址，不带 /chat/completions>
PFX_AGENT_MODEL_NAME=<供应商支持的模型 ID>
PFX_AGENT_MODEL_API_KEY=<本机 API Key>
```

适配器会向根地址追加 `/chat/completions`，并使用 Bearer Token。外部供应商地址必须使用 HTTPS；请只使用官方文档给出的 API 根地址和模型 ID。基础 Compose 的 Agent 网络默认无法访问公网。确认供应商、费用预算与网络出口后，显式增加可选的 `compose.agent-model-egress.yml` 覆盖文件，再只重建/启动 Agent：

```powershell
docker compose --project-name prizeflux --env-file deploy/.env.product --file deploy/compose.product.yml --file deploy/compose.agent-model-egress.yml --profile local-full up --build --detach --no-deps agent
```

该覆盖文件给 Agent 额外接入一个普通 Docker bridge 出口；它不是按域名限制的 egress firewall，启用后容器可能访问其他公网目标，因此仅在需要联调时使用。启动与健康检查本身不会验证 Key 是否有效，也不会主动调用模型；第一次真正发起 AI 请求才可能计费。不要用 `docker inspect`、完整 Compose 展开配置或终端录屏检查密钥，因为这些可能暴露环境变量。若要回到免费 Fake 模式，将 provider 改回 `fake`，移除命令中的 `compose.agent-model-egress.yml` 并重建 Agent。Agent 的模型调用轮数、时间和输出 token 已有限制；Fake 默认不会自动回退成真实模型，真实模型配置错误会明确失败。

这个组合配置目前只有 Compose 静态检查和代码级测试时，才可称为已实现。容器镜像构建、Nginx 运行配置检查及完整容器 E2E smoke 必须保存实际通过证据后才能勾选 P14-E；不以 `config --quiet` 代替运行验收。
