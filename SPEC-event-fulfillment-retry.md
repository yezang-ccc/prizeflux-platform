# Spec: event-fulfillment retry and manual-review loop

## Objective

Complete the local, independently runnable Worker path so accepted fulfillment events are durably recorded, retried after transient failures, and moved to a durable manual-review queue after permanent failure or retry exhaustion. The Campaign draw transaction remains unchanged. This is a demonstrable internal fulfillment simulation, not a claim of delivering goods through a real supplier.

## Assumptions and decisions

- Retry policy: at most 3 execution attempts, with deterministic exponential delays of 1 and 2 seconds between attempts. Time is injected so tests do not sleep.
- A permanent failure goes directly to `MANUAL_REVIEW`; a transient failure after the final attempt also goes to `MANUAL_REVIEW`.
- `MANUAL_REVIEW` has a durable compensation/review task keyed uniquely by fulfillment/draw. No automatic refund or external side effect is invented.
- Worker execution is an explicit port. The local demo implementation is labeled simulated and idempotent by stable draw/fulfillment ID. A real supplier adapter is out of scope.
- Worker remains a non-web process. Do not add an unauthenticated compensation controller; review items are inspectable through the documented local operational query until a separately designed authenticated operations API exists.
- MySQL remains authoritative. Claim/lease state is durable and supports multiple Worker processes; provider calls occur outside database transactions. A retry always reuses the stable fulfillment ID.

## Commands

- Build: from `backend`, `./mvnw.cmd -pl prizeflux-worker -am -DskipTests package`
- Targeted: from `backend`, `./mvnw.cmd -pl prizeflux-worker -am -Dtest=FulfillmentRetryPolicyTest,FulfillmentIdempotencyIntegrationTest,FulfillmentKafkaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Milestone regression: from `backend`, `./mvnw.cmd -q test`
- Cross-process smoke: from repository root, `./scripts/smoke-draw-worker-cross-process.ps1 -JavaHome <JDK-17-path>`

## Project structure

- `backend/prizeflux-worker/src/main/java/.../application`: execution port, retry policy, processing and review use cases.
- `backend/prizeflux-worker/src/main/java/.../infrastructure`: JDBC claims/leases, migrations and local simulated adapter.
- `backend/prizeflux-worker/src/test/java`: deterministic policy tests and MySQL/Testcontainers failure/recovery integration tests.
- `docs/runbooks`: local inspection and recovery instructions.

## Code style

Keep business policy independent from Spring and JDBC. Port operations use stable IDs and typed outcomes; infrastructure owns transaction boundaries and scheduling. A worker claim must be conditional and lease-fenced:

```java
var claim = repository.claimDue(now, leaseUntil, leaseToken);
if (claim.isEmpty()) return;
var result = executor.execute(claim.get().fulfillmentId(), claim.get().event());
repository.recordResult(claim.get().fulfillmentId(), leaseToken, result, now);
```

Never hold a database transaction open while calling the fulfillment executor.

## Testing strategy

- Pure tests: retry delay, attempt bounds, transient/permanent classification, allowed state transitions.
- MySQL integration tests: atomic claim with lease fencing, transient retry, exhaustion/permanent-to-review transition, unique compensation task, duplicate Kafka event, rollback/recovery.
- Existing authenticated Campaign→Outbox→Kafka→Worker→MySQL smoke must continue to pass; update its expected final state only if the simulator is enabled by the smoke profile and keep exactly-once ledger assertions.
- Run the full backend suite once at the milestone boundary, not once per small task.

## Boundaries

- Always: preserve Campaign as the draw/inventory source of truth; use MySQL for retry state; make local fulfillment visibly simulated; retain event/draw idempotency.
- Ask first: real supplier integration, refunds, changes to draw transaction/event schema semantics, public or remotely reachable operations endpoints, credentials/costs, cloud deployment.
- Never: retry non-idempotent external actions without a stable provider idempotency key; expose a public unauthenticated compensation endpoint; claim simulated fulfillment is real delivery.

## Success criteria

- A transient executor failure retries after persisted backoff and eventually succeeds, with no duplicate fulfillment row.
- A permanent failure or exhausted transient retry becomes `MANUAL_REVIEW` and creates exactly one durable review task.
- Expired leases are recoverable; stale lease holders cannot overwrite a newer result.
- Duplicate/replayed Kafka events do not create additional fulfillment/review effects.
- Local cross-process smoke, targeted MySQL integration tests, and milestone regression pass; docs state simulation and recovery limits accurately.

## Open questions

- Authenticated remote review/retry operations and Campaign status projection are intentionally deferred; the current focused scope does not authorize opening a Worker HTTP management surface.
- Real delivery-provider semantics and compensation/refund rules require a concrete supplier contract and separate approval.

## 实施结果（2026-09-24）

规格边界按本机可复现版本实现：3 次上限、1/2 秒退避、租约恢复与 fencing、永久/耗尽失败落入补偿审核队列。Worker 9 项定向测试通过；全后端回归 41 套件/281 项通过；两个独立 JVM 的 Campaign→Kafka→Worker 烟雾验证最终 `SUCCEEDED`。仅使用模拟履约适配器，远程审核入口和真实供应商仍延期。
