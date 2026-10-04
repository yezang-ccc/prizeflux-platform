# PrizeFlux Web

Vue 3 + TypeScript 演示前端。页面只通过 HTTP API 工作，不直连 MySQL、Redis 或 Kafka。

## 本机运行

1. 启动 MySQL、Redis、Kafka 和 Campaign（8081）。运营助手使用独立 Python Agent（8083）及独立 Agent MySQL schema；创建者草案校验仍使用 Java AI HTTP 宿主（8082）。Campaign/Java AI 初始化见 [`docs/runbooks/ai-service.md`](../docs/runbooks/ai-service.md)，Python 数据库账户、Alembic 和启动方式见 [`agent-service/README.md`](../agent-service/README.md)。
2. 普通参与者可在 `/register` 自助注册，用户名会统一转成小写，注册成功后返回 `/login` 登录。此入口不能创建运营账号；运营账号仍使用 [`docs/architecture/identity-bootstrap.md`](../docs/architecture/identity-bootstrap.md) 中的一次性初始化流程。Campaign 的 `local-full` 默认用 Redis 对注册来源 IP 限流为 5 次/10 分钟，Redis 故障时拒绝注册。该功能仍无邮箱验证、验证码与滥用监控，仅用于本机演示，不要直接公网开放。
3. 在本目录运行：

   ```powershell
   npm ci
   npm run dev
   ```

4. 打开 `http://127.0.0.1:5173/login`。Vite 将 `/api` 代理到 Campaign `localhost:8081`，将 `/ai-api`（剥除该前缀后）代理到 Java AI `localhost:8082`，将 `/agent-api`（剥除该前缀后）代理到 Python Agent `localhost:8083`。开发服务器只监听本机回环地址。

## 页面

| 角色 | 页面 | 能力 |
| --- | --- | --- |
| USER | `/campaigns`、`/campaigns/:id` | 活动发现、奖品和概率、抽奖、结果 |
| USER | `/me/history` | 当前账号的抽奖记录 |
| USER | `/me/campaigns`、`/me/campaigns/:id` | 创建自己的活动草稿、配置奖项、只读 Agent 校验、提交审核、查看状态与驳回理由 |
| OPERATOR | `/ops/reviews` | 查看待审活动，批准后公开发布或填写理由驳回 |
| OPERATOR | `/ops/campaigns`、`/ops/campaigns/:id` | 管理运营活动：创建、配置草稿、发布和关闭 |
| OPERATOR | `/ops/assistant` | Python Harness 只读活动助手；MySQL 持久会话、SSE 状态、显式恢复待执行 run；Fake 默认 |

创建者草案助手位于活动编辑页，仍由 Java AI 调用 Fake Model 与只读规则校验工具生成建议；需由用户明确保存，Agent 本身不能创建、提交、审核、发布或开奖。运营助手现已接入 Python 会话服务，两类 Agent 的入口和运行时不同。

路由守卫只用于改善导航；后端 JWT 与角色授权才是安全边界。JWT 仅放在当前标签页 `sessionStorage`，退出或服务端返回 401 时会清除。中奖结果已由服务端确认只代表业务记录，不代表真实兑换或供应商完成履约；用户页面不显示未经 Campaign 确认的 Worker 最终状态。

## 验证

```powershell
npm run test -- --run
npm run build
```

API 主路径须配合真实本机服务验证。Fake Model、前端 mock 测试与生产模型/公网部署无关。
