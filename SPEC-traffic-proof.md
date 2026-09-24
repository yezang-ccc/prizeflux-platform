# Spec: traffic-proof Redis 抽奖入口限流切片

状态：按用户已确认 Redis 纳入项目，并结合用户此前“自行复审后继续实施”的授权，先实现最小可验证的 `traffic-proof` 子切片。当前不实现展示缓存、Redis 库存预占或性能宣称。

## Objective

在 Campaign 已通过 JWT 认证的 `POST /api/draws` 入口，以服务端确认的 `USER.userId` 作为维度执行跨实例共享的 Redis 固定窗口限流。目的在于限制恶意/异常请求速率，不替代活动每日业务次数、幂等约束或 MySQL 库存事务。

固定窗口初始默认值为每用户每 60 秒最多 30 个请求，可通过配置调整。窗口从 Redis 接受该窗口首个请求时开始，并以该键的 TTL 作为窗口剩余时间，不依赖应用服务器时钟。超过限制返回稳定 429 错误；限流开启时 Redis 不可用必须安全失败为 503，不能退化成无限放行。响应包含安全的限流剩余额度和窗口重试秒数，不暴露 Redis 错误。

## Tech Stack

Java 17、Spring Boot 3.5、Spring Data Redis/Lettuce、Redis 7.4、Spring Security、Testcontainers。MySQL 继续作为抽奖资格、业务次数、幂等记录、库存与结果最终事实。

## Commands

- Targeted tests: `cd backend; $env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=DrawRateLimitFilterTest,RedisDrawRateLimiterIntegrationTest,ErrorCodeTest,GlobalExceptionHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- Campaign tests: `cd backend; $env:JAVA_HOME='D:\java\jdk-17'; .\mvnw.cmd -pl prizeflux-campaign -am test`
- Batch check: `git diff --check`; backend-wide test at the next campaign/traffic milestone, not after each task.
- Local Redis: `docker compose --env-file deploy/.env -f deploy/compose.foundation.yml up -d mysql redis`.

## Project Structure

- `application/DrawRateLimitDecision.java`: framework-free result value.
- `application/DrawRateLimiter.java`: application-facing rate-limit port.
- `infrastructure/traffic/RedisDrawRateLimiter.java`: one atomic Redis Lua script and fixed-window key construction.
- `infrastructure/traffic/DrawRateLimitFilter.java`: after JWT authentication, applies only to USER draw requests and maps errors safely.
- `infrastructure/traffic/DrawRateLimitConfiguration.java`: explicit enablement, defaults, disabled local/test adapter.
- `src/test/java/.../infrastructure/traffic`: HTTP filter behavior and real Redis concurrency tests.

## Code Style

Keep Redis concerns outside domain objects. The filter reads only an `AuthenticatedActor` already installed by JWT verification:

```java
if (actor.role() != AppUser.Role.USER) {
    chain.doFilter(request, response);
    return;
}
DrawRateLimitDecision decision = limiter.consume(actor.userId());
```

The client cannot supply the limiter identity. The Redis script increments one fixed-window key atomically; separate adapter instances share the same keyspace.

## Testing Strategy

- Unit/servlet-filter tests: only `POST /api/draws` for authenticated USER is limited; identity comes from server principal; OPERATOR, other routes, and disabled mode do not consume tokens; denial is 429; Redis failure is 503 with no exception details.
- Testcontainers Redis: many concurrent consumes through two adapter instances admit exactly the configured maximum; distinct users/windows are isolated; Redis counter is not treated as inventory.
- Existing MySQL draw integration continues to prove quota/idempotency/stock/outbox invariants; this slice must not alter its expected business semantics.
- Actual throughput improvement is **not** claimed until a later controlled benchmark records baseline, Redis-enabled result, environment, p50/p95/p99, throughput, error rate and correctness invariants.

## Boundaries

- Always: use authenticated server identity; atomic Redis operation; bounded fixed-window keys; fail closed (503) when enabled Redis is unavailable; retain MySQL as authoritative business storage.
- Ask first: switch to inventory reservation, add public/cloud Redis, enable paid infrastructure, change behavior beyond draw rate limiting, or claim quantitative performance gains.
- Never: trust client `userId`, use Redis as the final stock/quota/result record, fail open on Redis outage, store secrets in Compose or source.

## Success Criteria

1. Local Compose provides an isolated Redis 7.4 service bound to loopback; `local-full` enables the draw limiter with documented safe defaults.
2. Under concurrent calls sharing the same Redis, the admitted count never exceeds the configured fixed-window maximum.
3. A denied request never enters the application draw service; Redis outage while enabled produces a fixed 503 response.
4. Existing MySQL draw/idempotency/inventory tests remain green; no claim of performance improvement is made without a benchmark.

## Open Questions

No blockers for this additive slice. Fixed-window boundary bursts are an explicit tradeoff; token bucket/sliding-window alternatives may be evaluated only with later traffic evidence.

## 实施结果（2026-09-24）

T1～T4 已完成并按任务记录中的命令验证。全后端 37 套件、261 项通过；这证明功能与既有回归，不是吞吐量对比。部署 Redis 为本机 loopback Compose 服务，Testcontainers 中的 Redis 测试容器在测试结束后清理；没有声称常驻服务已启动或公开上线。
