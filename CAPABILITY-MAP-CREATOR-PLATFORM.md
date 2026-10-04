# Capability Map: PrizeFlux 用户创建与审核平台

状态：2026-09-25 用户确认并已实现首版：普通用户可创建和编辑自己的活动草稿；提交后由运营员审核，通过后公开发布。用户仍可参与其他已发布活动，不新增 `CREATOR` 登录角色。代码与回归证据见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)；真实登录后的本机浏览器全流程仍需现场验收。

## 能力边界

| 模块 ID | 职责 | 依赖 |
|---|---|---|
| `campaign-ownership` | 活动服务端发号、创建者归属、仅本人草稿查询/编辑/配置/提交 | Campaign、USER/OPERATOR 身份 |
| `campaign-review` | 待审队列、审核理由、通过即发布/驳回、不可篡改的审核记录 | `campaign-ownership`、抽奖配置验证 |
| `creator-console` | 用户的我的活动/新建/编辑/提交/状态与反馈；运营审核队列 | `campaign-ownership`、`campaign-review` |
| `creator-agent-copilot` | 用受控 Harness 检查并解释结构化草案；返回建议，不直接写库 | `campaign-ownership`、`harness-runtime`、`harness-safety` |

## 实施顺序

`campaign-ownership` → `campaign-review` → `creator-console` → `creator-agent-copilot`

## 共同不变量

- 不新增全局 `CREATOR` 身份角色：一个 `USER` 可参加活动，也可拥有活动。所有者只来自经 JWT 验证的 `ActorProvider`，不读客户端 `userId`。
- 新活动由服务端分配 ID。历史活动保留可空 `owner_user_id`，现有 OPERATOR API 保持兼容；新增 USER 创建的活动必须有 owner。
- 用户只可查看、编辑自己的 `DRAFT`/`REJECTED` 活动；提交后锁定编辑。仅 OPERATOR 可审核；通过时验证并冻结配置、原子发布，驳回需给用户可见理由。
- 已发布配置仍不可修改；关闭、库存、抽奖结果与 Outbox 不因本切片改变。参与入口仍只查已发布活动。
- Agent 的工具必须受服务端角色/资源边界控制。Agent 只能返回草案建议与确定性校验结果；用户明确确认后由普通业务 API 创建/编辑草稿。Agent 不能提交审核、审核、发布、开奖或扣库存。
- 继续使用本仓库视觉令牌（墨蓝 `#1E2A3B`、蓝 `#355FC4`、暖金 `#E8A943`、孔雀青 `#247B70`、边界灰 `#DCE3EC`），沿用 Manrope/系统中文无衬线。创建端左对齐、表单配合可见状态轨；审核端突出待处理内容、风险说明和明确的通过/驳回动作，不堆同构圆角卡片。
