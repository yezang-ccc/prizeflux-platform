# Spec: `vue3-product-web`

状态：演示前端及创建者平台首版已实现；2026-09-28 起按 Element Plus/Pure Admin 模式分批收整 UI，登录后真实浏览器全流程需在当前运行服务加载新版本后确认，准确验证见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

## Objective

在仓库 `web/` 提供可运行的 Vue 3 单页应用：普通 USER 注册/登录、浏览抽奖、管理自己的活动草稿及个人历史；OPERATOR 登录、审核用户草稿和处理全站活动。USER 创建者身份通过每条活动的 owner 归属表达，不增加可自助选择的角色。只调用 Campaign HTTP API，不连接数据库、Redis 或 Kafka。注册入口按 IP Redis 限流但仍无邮箱验证、验证码与滥用监控，限本机演示。

## Tech Stack

Vue 3 Composition API、TypeScript、Vite、Vue Router、Pinia、原生 `fetch`、Element Plus 与少量页面 CSS；Vitest、Vue Test Utils、jsdom 作组件/状态测试。页面控件优先用 Element Plus，不另造按钮、提示、卡片等基础交互组件。Node/npm 由本机已有运行时提供。生产静态站部署不在本规格。

## User Flows and Routes

- `/login`：用户名/密码登录 `POST /api/auth/login`，再用 `/api/auth/me` 获取服务端角色；退出时清除前端会话。
- `/register`：通过 `POST /api/auth/register` 创建普通用户，用户名首尾空格会去除且统一转小写；前端校验用户名和密码并显示明确错误，成功后转回登录页；不能提交/提升角色，无自动登录。服务端按来源 IP 限流并返回剩余时间提示。
- `USER`：`/campaigns` 活动列表 → `/campaigns/:id` 活动详情/奖品说明/抽奖 → `/me/history` 个人抽奖历史。每次新抽生成新的 `requestKey`；重试同一次请求复用同一 key；收到结果前禁用重复提交。
- `USER` 创建者：`/me/campaigns` 我的活动与新建 → `/me/campaigns/:id` 编辑 DRAFT 或 REJECTED → 读取配置、请求只读 Agent 校验、明确保存/提交审核 → 查看 PENDING_REVIEW、驳回理由或 PUBLISHED 状态。前端不能编辑待审/已发布配置。
- `OPERATOR`：`/ops/campaigns` 全站活动，`/ops/reviews` 用户活动审核队列，保留原运营内部活动创建/管理兼容。审核通过才对参与者公开；驳回须输入理由。运营全局管理权限不可由普通注册获得。
- 路由守卫用于友好导航，不作为权限边界；Campaign 每个写/读入口继续验 JWT 与角色。401 清会话并回登录，403 显示权限说明，不把原始后端异常展示给用户。
- 中奖结果只说明中奖记录已写入；演示使用模拟履约，历史页面首版不显示未可靠回写的最终 Worker 状态，不承诺真实兑换/发货。

## Visual Design Plan

页面采用 Element Plus 的标准内容页、表单、卡片、状态提示和响应式控件。业务层级以“活动列表 → 详情规则/概率 → 即时或排队参与 → 服务端结果”为主；不添加大幅自制科技插画、虚构指标或装饰性票券图形。

- Palette：雾白 `#F5F7FA`（页面底）、深靛 `#1E2A3B`（文字/导航）、钴蓝 `#355FC4`（主动作）、孔雀青 `#247B70`（成功/可参与）、暖金 `#E8A943`（中奖提示）、边界灰 `#DCE3EC`。
- Type：系统无衬线中文（Microsoft YaHei/系统 sans）；拉丁标题可用 `Inter` 若本机可用，未安装时回退系统字族。无需外链字体。
- Layout：参与端使用清楚的页面标题、活动列表、详情信息与固定可辨识的主动作；运营端使用工作区与分组表单；移动端折叠为单列。
- Wireframe：

```text
参与端： [PrizeFlux 导航]  [活动时间/状态] [奖品清单]
         [个人历史]      [抽奖号码/结果] [抽一次]

创建端： [我的活动] [状态轨：草稿 → 待审 → 公开 / 需修改]
         [活动资料 | 奖品与概率 | Agent 检查建议 | 保存/提审]

运营端： [全站活动 / 待审核 / 活动助手] [活动与奖项事实]
                                           [通过并公开 / 写理由驳回]
```

- Self-critique：不要用自制主视觉替代真实活动信息；Element Plus 卡片只用于分组，不给每个文字块都套卡片。状态需要文字说明，重要正文保持可读字号。

## Commands and Structure

```powershell
Set-Location -LiteralPath '.\web'
npm ci
npm run dev
npm run test -- --run
npm run build
```

预期结构：`web/src/{api,auth,router,stores,views,components,styles}`；`web/src/api` 统一封装 `/api` 请求、Bearer 与安全错误；`web/tests` 放组件与状态测试。Vite 将 `/api` 转发到 Campaign `localhost:8081`、`/ai-api` 转发 AI `localhost:8082` 并剥离 `/ai-api` 前缀，避免开发态 CORS 依赖；生产部署/反向代理另行规格。

## Code Style

使用小型 Vue SFC 与 Composition API；API DTO 单独使用 TypeScript 类型。状态枚举集中映射中文展示；输入校验用于及时反馈，服务端仍是最终校验方。示例：

```ts
export async function request<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  })
  if (!response.ok) throw mapApiError(response.status)
  return response.json() as Promise<T>
}
```

## Testing Strategy and Success Criteria

- Vitest 覆盖认证状态/角色守卫、401/403/超时映射、抽奖幂等键与防重复点击、奖品/概率表单边界、空态与失败态、活动历史展示。
- 服务端代理与 AI 地址重写有配置测试或人工验证；`npm run test -- --run` 与 `npm run build` 成功。
- 浏览器人工走通 USER 与 OPERATOR 主路径（API 由本机演示环境提供）；确认键盘焦点、窄屏布局、减少动态效果偏好、无控制台未处理错误。
- 页面只使用真实 API 响应；无假活动种子数据、无把 Fake Model/模拟履约伪装为真实能力。

## Boundaries

- Always：`sessionStorage` 保存短期 Bearer token；Vue 默认文本插值渲染 AI 文本，不用不可信 `v-html`；提交期间禁用重复动作；后端权限始终有效。
- Ask first：运营账号自助注册、注册策略或限流阈值调整、Creator 付费/KYC、真实模型费用、真实外部履约、生产托管。
- Never：把 API 密钥/JWT 写入源码、localStorage、日志或提交；信任前端角色；前端自行判定奖品概率/扣库存；展示未验证的生产数据。

## Open Questions

1. Creator 创建活动使用 V4 数据库服务端发号；遗留 OPERATOR API 保持现有 ID 合同。公开 DTO 应继续避免 long 超 JS 安全范围造成精度损失。
2. API 异步发奖的 Campaign 状态投影当前尚未验证/打通；首版历史暂不展示最终履约状态。
