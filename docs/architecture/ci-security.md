# CI 与秘密安全基线

## 当前真实状态

- 工作流配置已存在于 `.github/workflows/ci.yml`。
- 本机可执行 Maven Wrapper 构建、测试和秘密基线检查。
- 当前仓库尚未产生 GitHub Actions 远程运行记录；因此不能宣称“GitHub CI 已通过”。

## CI、构建、测试、制品与部署

```text
代码变更
  → CI 自动编排构建、测试和检查
  → 制品（例如 jar）
  → 部署到测试/预发布/生产环境
```

CI 不等于部署。CI 的作用是尽早在相对一致的环境中发现问题；部署负责让已经构建的制品在目标环境实际运行。

## 工作流安全设计

- 仅声明 `contents: read`；未声明的 `GITHUB_TOKEN` 权限为 `none`。
- 使用 `push` 与 `pull_request`，不使用会在不可信 PR 上暴露高权限上下文的 `pull_request_target`。
- `actions/checkout` 与 `actions/setup-java` 均固定到完整提交 SHA。完整 SHA 是不可变引用，降低可变标签被移动带来的供应链风险。
- 不读取 GitHub Secret，不进行部署，不上传制品，不写回仓库。

GitHub 官方建议显式声明最小权限，并将 Action 固定到完整提交 SHA；完整 SHA 需要由维护者后续按来源与更新策略审阅。详见 [GitHub Actions 安全加固文档](https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions)。

## 秘密处理边界

| 位置 | 是否可提交 | 用途 |
|---|---|---|
| `.env.example` | 可以 | 变量结构与安全默认值模板。 |
| `.env` | 不可以 | 本机真实密码、Token 等运行时配置。 |
| GitHub Secret | 不提交 | 未来远程工作流或部署所需的受保护敏感值。 |
| Docker 镜像 | 不可以打入秘密 | 镜像可被导出、检查或误发；生产秘密应在运行时注入。 |

本地运行 `scripts/check-secrets.ps1` 时会扫描已跟踪/暂存及未忽略的未跟踪文件；GitHub Actions 在干净检出中因此扫描所有已跟踪文件。脚本只覆盖少量高置信规则、不输出命中正文，不能证明没有任何秘密，也不能替代专业扫描、泄露后的撤销轮换或历史清理。

## 验证口径

| 证据 | 可以说什么 |
|---|---|
| 本机 `mvnw.cmd test` 通过 | 本机后端测试通过。 |
| 本机 `check-secrets.ps1` 通过 | 当前可提交候选文件未命中该脚本的最小规则。 |
| GitHub Actions 有对应提交的成功记录 | 该提交的远程 CI 通过。 |
| 目标环境健康检查通过 | 该环境的应用实例可用，不自动等同于完整生产验收。 |
