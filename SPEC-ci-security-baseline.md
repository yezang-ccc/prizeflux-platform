# Spec：D1-05 持续集成与秘密安全基线

状态：规格、计划、任务均已批准；本机实现、验证和本轮学习验收完成。GitHub 远程运行、部署及专业扫描器执行尚未验收。

## 1. 背景

PrizeFlux 已有可运行的 Maven 多模块后端和一条 Campaign 健康接口测试，但构建验证目前只在本机手动执行。项目也已通过 `.gitignore` 和 `.env.example` 约定不提交本地秘密；仍需要在版本库中形成最小、可审计的自动验证基线。

本任务只建立 CI 与秘密安全基线，不部署、不访问腾讯云、不创建或使用真实 API Key，也不公开推送仓库。

## 2. 目标

1. 新增一个 GitHub Actions 工作流，使未来的 `push` 和 `pull_request` 可以自动验证后端 Maven 构建与测试。
2. 工作流明确使用 Java 17 与项目 Maven Wrapper，而不是依赖运行器的全局 Maven。
3. 工作流保持最小权限，不读取、打印或要求任何真实秘密。
4. 建立可提交的秘密扫描规则和本地验证说明，使 `.env`、密钥文件和常见凭据模式有第一层防护。
5. 在未推送远程仓库前，以工作流语法、Wrapper 构建和本地扫描证明配置可读；不伪造“GitHub CI 已通过”。

## 3. 范围

### 包含

- `.github/workflows/ci.yml`：后端验证工作流。
- `.gitleaks.toml`：秘密扫描的最小规则配置。
- `scripts/check-secrets.ps1`：只读的本地秘密基线检查入口。
- `docs/architecture/ci-security.md`：说明 CI、制品、部署、权限和秘密边界。
- 对 README 的最小更新：说明本地/远程验证状态的差异。

### 不包含

- GitHub 公开推送、创建仓库、添加 GitHub Secrets、部署或登录腾讯云。
- 构建 Docker 镜像、前端 CI、发布制品、自动部署、依赖自动升级。
- 声称 Gitleaks 或 GitHub Actions 已在远程执行成功。
- 将真实数据库密码、Token、私钥、服务器 IP、Joplin Token 或 AI Key 写入工作流、日志或配置。

## 4. 设计

### 4.1 CI 工作流

触发条件：未来的 `push` 与 `pull_request`；当前未推送时不产生远程运行记录。

执行步骤：

```text
checkout 源码
  → 设置 Java 17
  → 执行 backend/mvnw.cmd -DskipTests validate
  → 执行 backend/mvnw.cmd test
  → 执行秘密扫描
```

工作流权限默认限制为 `contents: read`。不使用 `pull_request_target`，不向 PR 中注入秘密。

### 4.2 秘密安全边界

- `.env`、`application-local.yml`、私钥和证书文件继续由 `.gitignore` 忽略。
- `.env.example` 只保留变量名、安全样例或空值。
- `scripts/check-secrets.ps1` 只检查 Git 已跟踪文件与暂存区候选文件；不打印匹配到的完整秘密。
- 秘密扫描是预防层，不替代泄露后的撤销、轮换和历史清理。

### 4.3 真实状态表达

| 状态 | 可以声明 | 不可以声明 |
|---|---|---|
| 本地工作流文件完成 | 工作流已创建并做静态审阅；本地 Maven 验证通过。 | GitHub Actions 已运行通过。 |
| 推送至远程后 | 使用 GitHub Actions 实际运行记录证明具体提交通过。 | 以旧提交或本机结果代替远程运行结果。 |
| 发现真实秘密 | 立即撤销/轮换，停止暴露，评估 Git 历史。 | 只删除文件后声称风险已消失。 |

## 5. 验收标准

1. 工作流使用 Java 17、`backend/mvnw.cmd`，不调用全局 `mvn`。
2. 工作流、文档与脚本中不存在真实秘密；`git check-ignore` 能证明 `.env` 被忽略而 `.env.example` 可提交。
3. 本地运行 Maven Wrapper 的 `validate` 与 `test` 均通过。
4. 本地秘密检查只输出文件路径、规则类别和安全处理建议，不回显秘密正文。
5. 学习者能区分：CI、构建、测试、制品、部署、GitHub Secret 与本地 `.env`。
6. 所有远程运行状态都如实标为“尚未运行”，直到有 GitHub Actions 证据。

## 6. 学习与验收阶梯

| 层级 | D1-05 内容 |
|---|---|
| L0 | CI 与本地命令、构建/测试/制品/部署、Secret 与 `.env` 的区别。 |
| L1 | 不计分复述后，解释为什么 Wrapper 和最小权限适合 CI。 |
| L2 | 阅读/运行本地验证入口，理解工作流每一步的输入输出。 |
| L3 | 分析 PR 中恶意代码、秘密误提交、依赖下载失败等边界。 |
| L4 | 用项目事实说明 CI 为什么不能取代本地调试、测试和部署验证。 |

## 7. 风险与停止条件

- 如果实现需要真实 GitHub Token、仓库权限或远程推送，停止并由学习者明确授权。
- 如果扫描命中疑似真实秘密，停止后续 Git 操作，仅报告文件路径/类别并要求轮换或确认。
- 如果新增第三方 GitHub Action 的供应链风险无法说明，先降级为本地验证，不把外部 Action 引入工作流。
