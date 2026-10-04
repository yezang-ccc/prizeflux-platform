# Spec: Agent 长运行授权生命周期

## Objective

保证已受理的 Agent run 在排队、检索、模型生成和结果落库期间不会仅凭开始时的 Actor 持续运行。Campaign 是 JWT 与角色的唯一权威；Agent 不解析、签发或续签 JWT。

成功条件：Worker claim 后先重验身份；执行期间在有界间隔通过 Campaign `/api/auth/me` 复核原始 Bearer；Token 过期/撤销、角色或用户身份变化、Campaign 身份服务不可用时，尽早停止当前运行且不保存答案/引用；即使恰好在周期检查之间完成，也必须在保存前复核一次。Bearer 仍仅驻留请求/Worker 内存，不写数据库、日志或事件。

## Assumptions

1. 维持已批准产品架构与 P8 合同：Campaign 控制用户身份与权限；Agent run 失败不自动重新调用模型。
2. 短期访问令牌由 Campaign 签发，运行时无需也不得读取 JWT 私钥或自行信任客户端声明。
3. 运行期间身份检查通过 `IdentityPort` 完成；身份服务不可用按 fail-closed 处理，产生安全错误而非返回部分答案。

## Tech Stack

Python 3.12–3.14、FastAPI Worker、现有 `IdentityPort`、Campaign HTTP client 与 Agent MySQL RunStore。不得新增依赖或数据库列。

## Commands

```powershell
cd agent-service
uv run --locked pytest tests/test_run_executor_authorization.py -q
uv run --locked pytest -m 'not mysql' -q
uv run --locked ruff check src tests
uv run --locked mypy src
```

## Project Structure

- `agent-service/src/prizeflux_agent/application/run_executor.py`：Worker 周期复核及写库前身份复核。
- `agent-service/src/prizeflux_agent/config.py`、`api/app.py`：有界身份复核间隔配置与装配。
- `agent-service/src/prizeflux_agent/application/errors.py`：安全的 run 失败代码。
- `agent-service/tests/test_run_executor_authorization.py`：过期、角色变化、Campaign 不可用、保存前失效及合法完成。
- `agent-service/README.md`、`PROJECT_STATUS.md`、交付计划：配置与授权边界。

## Code Style

只依赖显式身份端口和服务器配置；不捕获并忽略授权错误，也不将其误报为用户取消。

```python
current_actor = await identity_client.verify(bearer_token)
if current_actor != original_actor:
    raise HarnessExecutionError("authorization_changed")
```

使用安全错误代码，不记录 Bearer、JWT claims 或 Campaign 响应正文。

## Testing Strategy

- 使用可控 IdentityPort 与内存 RunStore/Runtime，不访问本机数据库或真实 Campaign。
- 覆盖 run 初始校验、执行中 token 过期、用户/角色变化、身份服务不可用和最终持久化前过期。
- 在真实 Harness 测试中确认周期授权失败会取消正在等待的模型协程，而不是只阻止后续保存。
- 每个拒绝路径断言 `complete_run` 未调用且 `fail_run` 只收到已定义安全代码；健康路径只保存一次。
- 最后运行 Agent 非 MySQL 回归、Ruff、mypy 和 `git diff --check`。

## Boundaries

- Always：使用 Campaign 的实时服务端身份；身份状态不确定时停止；先 fail_run 再结束 Worker；保持已有 Fencing/CAS/取消协议。
- Ask first：改成可续签/刷新令牌、持久化 Bearer、改变 Campaign 身份/角色合同。
- Never：在 Agent 本地验签并独立决定权限；身份错误后自动换 Token/重跑付费模型；在 Worker 日志/数据库/SSE 暴露令牌。

## Success Criteria

1. 每个 run 至少在 claim 后和完成写库前向 Campaign 复核身份。
2. 对正在等待/执行的操作按有限间隔复核；已过期、撤销、角色改变或身份服务故障均阻止最终结果持久化。
3. 失败状态只含安全错误码，history 不出现失败答案/引用，Bearer 不落库。
4. 自动化拒绝/成功路径测试、非 MySQL 回归、Ruff、mypy 通过；未修改 Java JWT 签发规则或数据库 schema。

## Open Questions

无。间隔固定默认为 10 秒并由有界本地配置调整；未连接生产 Campaign，也未验证实际用户 Token 的 TTL。
