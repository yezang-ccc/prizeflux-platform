# Spec: `creator-console`

状态：产品方向已确认；实现由 [`CAPABILITY-MAP-CREATOR-PLATFORM.md`](CAPABILITY-MAP-CREATOR-PLATFORM.md) 跟踪。继承现有 PrizeFlux Vue 3 设计，不另建 SPA。

## Objective and Structure

在现有 `web/` 内分开参与、创建和审核工作。普通 USER 导航提供“发现活动 / 我的记录 / 我的活动”；创建者能新建、编辑、配置、提交和查看审核理由。OPERATOR 导航提供“全站活动 / 待审核 / 活动助手”。后端服务端权限是唯一安全边界。

## Routes and Contract

- `/me/campaigns`：owner-scoped 列表与状态轨，并在同页创建活动基础草稿；`/me/campaigns/:id`：编辑本人的 DRAFT/REJECTED 活动、配置奖项、调用只读草案助手并提交审核。
- `/ops/reviews`：待审列表与选中项详情，展示奖品/概率/日期摘要和创建者；通过/驳回动作要求显式决策，驳回理由为去空格后 3–500 字。
- PENDING_REVIEW 明确显示已锁定并等待运营；PUBLISHED 显示不可编辑；REJECTED 显示运营理由并提供修改入口。
- 手机窄屏单栏、键盘焦点可见、AI 和所有动态遵守 reduced motion；加载/空/错误/冲突都有可行动提示。

## Tech and Commands

Vue 3、TypeScript、Vite、Vue Router、Pinia 与 Vitest。页面 API 使用现有 Campaign client，不直连数据基础设施。

```powershell
Set-Location -LiteralPath '.\web'
npm run test -- --run
npm run build
```

## Visual Direction

保留墨蓝 `#1E2A3B`、蓝 `#355FC4`、暖金 `#E8A943`、孔雀青 `#247B70` 与边界灰 `#DCE3EC`；Manrope/系统中文无衬线；宽屏左对齐、内容区域清晰分组。创建工作区用一条步骤/状态轨迹表现“草稿—审核—公开”，不使用通用卡片墙。审核页把待决策活动内容与批准/驳回放在同一水平视线，驳回动作视觉克制并要求理由。

```text
参与：发现活动 | 我的记录 | 我的活动
我的活动：状态筛选 ─ 草稿/待审/已发布/需修改
创建：活动资料 | 奖项与规则 | Agent 校验建议 → 保存草稿 → 提交审核
运营：全站活动 | 待审核队列 | 活动助手
审核：活动事实与校验结果 | 决策说明 | 通过并发布 / 驳回并说明
```

## Testing Strategy, Boundaries and Success Criteria

Vitest 覆盖路由/导航、owner-only 列表 API、所有状态/禁用动作、失败和驳回理由；浏览器走通 USER 自建草稿→审核→发布→活动目录可见以及 OPERATOR 审核。始终不在前端计算授权。禁止展示其他 owner 的草稿、伪造审核状态、或隐藏发布/库存边界。

成功标准：USER 与 OPERATOR 页面/接口操作各自清楚；拒绝和待审状态可解释；移动布局可用；既有抽奖浏览不回归。
