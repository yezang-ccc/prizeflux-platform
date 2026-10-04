# PrizeFlux Copilot (Python)

Independent Python service for the read-only Agent/Harness product line. Implemented slices include the application foundation, a LangGraph control-flow adapter, a project-owned per-run read-only tool allowlist, execution budgets, a deterministic Fake provider, OpenAI-compatible model/embedding adapters, Java Campaign identity/read/validation clients, bounded conversational context, an owner-scoped MySQL RunStore with idempotent admission, fencing, events, cancellation and recovery, plus P9 index build, P10 hybrid retrieval/ACL/revocation, P11a Harness evidence integration, and P12 offline lexical evaluation/CI. K2 adds an optional pinned local Chinese CPU embedding adapter; its weights were verified and smoke-tested on the Windows host, but no real Qdrant active index has been built. Retrieval defaults off; semantic retrieval/answer quality and real RAG availability remain unverified. This is not a public-production release.

Python domain and application contracts must remain independent of FastAPI, database clients, and model SDKs. `uv.lock` records exact resolved dependencies; update it only with uv after changing `pyproject.toml`.

## Local setup

Use Python 3.12–3.14 and uv. If uv is not installed, follow the [official Windows installation guide](https://docs.astral.sh/uv/getting-started/installation/). From this directory:

```powershell
uv sync --locked --group dev
uv run --locked pytest
uv run --locked ruff check src tests
uv run --locked mypy src
uv run --locked python -m prizeflux_agent
```

The service binds to loopback by default. `GET /health/live` reports process liveness; `GET /health/ready` reports configuration readiness, safe `model_provider`, and whether knowledge retrieval is configured, never credentials. `Fake Model` is the default and makes no external requests. The optional OpenAI-compatible adapter and Campaign client are covered by mocked HTTP contract tests; no real provider has been called. Every Agent request delegates Bearer verification to Campaign `/api/auth/me`, and session/run reads are owner-scoped. `GET /api/ai/v2/runs/{id}` returns role-filtered source metadata for a completed run; it never returns retrieved passage text. Routes also include session create/read/delete, run submit/resume/cancel and SSE status events. A run can be explicitly resumed only while still `ACCEPTED`; running/terminal calls are not replayed. Each session permits one active run; submissions use `clientMessageId` and `expectedVersion`; run acceptance returns HTTP 202. Per process, the default is at most 4 executing plus 16 waiting runs, with at most 4 outstanding runs per owner. New work reserves capacity before MySQL acceptance; excess gets HTTP 429 `agent_capacity_exceeded` and `Retry-After: 2` without creating an ACCEPTED row. Same-key/same-payload replay returns the original run and does not create another reservation; if an older ACCEPTED run is not present in local memory, it is dispatched only when capacity is available, otherwise it remains resumable. Model/tool awaits observe persisted cancellation, and expired running leases are marked interrupted rather than automatically replayed. Session deletion returns 204 for idle sessions; an active run is hidden and cancelled first (202), then its data is deleted.

### Container image (P14-E, application slice)

The first container slice builds only the Python Agent image; it does not start services or expose a host port. From this directory, build it with `docker build -t prizeflux-agent:local .`. The image uses version-tagged Python 3.14.6 and uv 0.12.19, runs as UID 10001, listens on port 8083 inside the container, and has a liveness-only Docker health check. The Compose profiles, migration job, immutable image digests, and same-origin reverse proxy are still pending; do not treat this image alone as a complete deployment.

For local container traffic, `PFX_AGENT_CAMPAIGN_INTERNAL_HTTP_ORIGIN` and `PFX_AGENT_QDRANT_INTERNAL_HTTP_ORIGIN` each trust only their own exact `http://host:port` origin and must match the corresponding base URL. Both exceptions are rejected outside `ENVIRONMENT=local`; neither relaxes model or Embedding endpoint policy. Campaign and Qdrant requests never follow redirects.

Do not expose the service publicly yet. Capacity reservations and per-owner counts are process-local: operate one Agent process/worker unless a shared MySQL admission lease/counter is implemented and tested. Multiple replicas multiply the configured limits; this is not a global quota. A process crash releases its in-memory reservations; previously accepted rows remain owner-visible and require explicit resume, which is capacity checked. The server deliberately does not persist user tokens or autonomously resume a paid model call. Deployment hardening, multi-instance global admission, and load/failure tests remain outstanding. Status events intentionally contain no prompt or model text; source metadata is available only through the owner-scoped run API. The browser stores only a bounded list of session/run identifiers in tab-scoped `sessionStorage`; prompts and answers are loaded from the owner-scoped service API.

### Bounded run admission (P14-D)

The default per-process limits are 4 executing runs, 16 waiting runs, and 4 outstanding runs per owner. Configure them with `PFX_AGENT_MAX_CONCURRENT_RUNS`, `PFX_AGENT_MAX_WAITING_RUNS`, and `PFX_AGENT_MAX_RUNS_PER_OWNER`. New requests check the owner-scoped idempotency key and reserve capacity before persisting a new run. When full, the API returns HTTP 429 `agent_capacity_exceeded` with `Retry-After: 2`; it does not create an ACCEPTED row. Completed, failed, cancelled, and deleted queued work releases its local reservation. A replay returns the original run without an additional reservation; an older ACCEPTED run absent from this process is dispatched only when capacity is free, otherwise it remains available for explicit resume.

These limits are process-local. Run one Agent process/worker unless a shared MySQL admission lease/counter is implemented and tested; multiple replicas multiply the configured capacity. This is not a multi-instance quota or public-production readiness claim.

## Configuration

Settings use the `PFX_AGENT_` environment prefix. Foundation settings are `ENVIRONMENT`, `HOST`, `PORT`, `LOG_LEVEL`, `CAMPAIGN_BASE_URL`, and the optional exact-origin `CAMPAIGN_INTERNAL_HTTP_ORIGIN`. `MODEL_PROVIDER` defaults to `fake`. To select the optional HTTP adapter, configure `MODEL_PROVIDER=openai-compatible`, `MODEL_BASE_URL`, `MODEL_NAME`, and `MODEL_API_KEY`; remote base URLs must use HTTPS, while HTTP is allowed only for loopback. Never commit `.env` files or provider credentials. Health responses do not echo configuration or secrets.

Harness limits are server-controlled through `HARNESS_TOTAL_TIMEOUT_SECONDS`, `MODEL_TIMEOUT_SECONDS`, `HARNESS_MAX_MODEL_CALLS`, `HARNESS_MAX_TOOL_CALLS`, `HARNESS_MAX_INPUT_CHARS`, `HARNESS_MAX_CONTEXT_CHARS`, `HARNESS_MAX_OUTPUT_CHARS`, `HARNESS_MAX_TOTAL_OUTPUT_TOKENS`, `HARNESS_MAX_TOOL_RESULT_CHARS`, and `HARNESS_MAX_OUTPUT_TOKENS_PER_CALL`. Run admission additionally uses `PFX_AGENT_MAX_CONCURRENT_RUNS` (default 4), `PFX_AGENT_MAX_WAITING_RUNS` (default 16), and `PFX_AGENT_MAX_RUNS_PER_OWNER` (default 4). Agent workers revalidate the original bearer with Campaign every `PFX_AGENT_AUTHORIZATION_RECHECK_INTERVAL_SECONDS` (default 10, max 60) while a run is active and again before persisting its answer; revoked/expired or changed identity and Campaign outages fail closed. Knowledge retrieval remains disabled unless `PFX_AGENT_KNOWLEDGE_RETRIEVAL_ENABLED=true`; enabling it requires the dedicated Agent MySQL schema, a configured local FastEmbed model or OpenAI-compatible Embedding endpoint, and Qdrant URL. Remote embedding may incur cost; local embedding uses CPU. There must be a built active index first; without one, the run fails closed rather than answering without evidence. The graph runs with implicit LangSmith callbacks disabled; this prevents prompts and tool observations from being sent to remote tracing without an explicit project decision.

## Curated knowledge index (P9)

The manifest at `knowledge/manifest.json` is the only corpus allowlist. The initial documents are public campaign help and an operator-only guide. `validate` is fully offline: it checks paths, scopes, file limits, deterministic chunks and BM25 snapshot serialization without contacting MySQL, an embedding service, or Qdrant.

```powershell
uv run --locked python -m prizeflux_agent.knowledge_cli validate
uv run --locked python -m prizeflux_agent.knowledge_cli --help
```

Index publication is a separate, intentionally explicit operation. It requires the dedicated Agent MySQL schema migrated to head, Qdrant, and a configured embedding adapter. Build may incur provider charges and writes versioned collections, so the command refuses to run without `--confirm-external-calls-and-writes`. For the local CPU option, first install the optional dependency and explicitly prepare verified weights:

```powershell
uv run --locked --extra local-embedding python -m prizeflux_agent.knowledge_cli prepare-local-model --confirm-download
$env:PFX_AGENT_EMBEDDING_PROVIDER = 'local-fastembed'
$env:PFX_AGENT_EMBEDDING_MODEL_PATH = (Resolve-Path '.data/models/bge-small-zh-v1.5-46fbe35').Path
# Configure PFX_AGENT_QDRANT_URL and the dedicated PFX_AGENT_DATABASE_URL before building.
uv run --locked --extra local-embedding python -m prizeflux_agent.knowledge_cli build --confirm-external-calls-and-writes
```

Model download is not performed at Agent startup or index-build time. The optional local-embedding container dependency is included only with `docker build --build-arg PFX_WITH_LOCAL_EMBEDDING=true -t prizeflux-agent:local-embedding .`; K3 must mount the verified model directory read-only and set its absolute container path. This container variant has not yet built successfully on this machine because Docker's PyPI connection failed; do not treat it as verified.

For the existing external HTTP option:

```powershell
$env:PFX_AGENT_EMBEDDING_PROVIDER = 'openai-compatible'
$env:PFX_AGENT_EMBEDDING_BASE_URL = 'https://<provider>/v1'
$env:PFX_AGENT_EMBEDDING_MODEL = '<embedding-model>'
$env:PFX_AGENT_EMBEDDING_REVISION = '<immutable-model-revision>'
$env:PFX_AGENT_EMBEDDING_DIMENSION = '<model-dimension>'
$env:PFX_AGENT_EMBEDDING_API_KEY = '<provider-key>'
$env:PFX_AGENT_QDRANT_URL = 'http://127.0.0.1:6333'
# Configure PFX_AGENT_DATABASE_URL for the dedicated Agent database as above.
uv run --locked python -m prizeflux_agent.knowledge_cli build --confirm-external-calls-and-writes
```

Remote endpoints must use HTTPS; the HTTP embedding adapter requires a key for remote services. Its revision field is an operator-supplied version label and must correspond to an actually pinned model deployment; it does not force a third-party service to honor that revision. The local adapter pins its own model identity and checks all seven file hashes; see [K2 decision](../docs/architecture/local-embedding-k2.md). No Qdrant active index has been built yet. The vector payload contains metadata only; source text remains in Agent MySQL. Failed builds leave the prior active pointer unchanged. P10 retrieval applies role prefilters, MySQL ACL/hash/revocation rechecks and BM25+dense RRF. P11a connects the retriever to Harness; an answer stores metadata only, and the UI shows source titles/sections/scope/version hash without returning passage text. This code path remains off until explicitly enabled and a real index is built.

The optional local container overlay is `deploy/compose.agent-knowledge.yml`; it adds internal-only Qdrant, a read-only verified model mount, and separate persistent Qdrant/BM25 volumes. It has passed static Compose validation but has not run here because Docker Desktop's configured proxy is unreachable. After a real build, `python -m prizeflux_agent.knowledge_cli verify-active` checks the MySQL active pointer, BM25 checksum/version/count, and Qdrant point count without writing or calling a provider. This does not replace a retrieval and role-isolation smoke test.

## Offline knowledge evaluation (P12)

The fixed evaluation set at `evaluations/knowledge-eval-v1.json` exercises relevant section retrieval, no-evidence queries, and a USER query whose matching OPERATOR-only material must remain invisible. Run it locally:

```powershell
uv run --locked python -m prizeflux_agent.knowledge_eval
uv run --locked python -m prizeflux_agent.knowledge_eval --top-k 3 --output .data/knowledge/evaluation.json
```

By default the command prints deterministic JSON and writes no file. An explicit output path is created only if it does not already exist. It uses the curated manifest, chunker and BM25 only; there is no database/network/provider call. The report fingerprints the dataset/corpus and records BM25/chunker/index revisions, while marking model and prompt as `not_run` and embedding as `not_used`. This checks lexical retrieval/evidence contracts only, not dense retrieval or generated answer quality. The GitHub workflow runs the locked Agent tests and this offline gate; remote CI has not been observed until a push.

Session cleanup uses `SESSION_RETENTION_DAYS` (default 30, range 1–365) and `SESSION_CLEANUP_INTERVAL_SECONDS` (default 3600). Cleanup is a hard delete of old session, run, and event rows; verify applicable privacy and backup-retention requirements before any public deployment.

## Agent session database

Sessions and accepted runs live in a dedicated MySQL schema, separate from PrizeFlux Campaign/Worker tables and account grants. Configure `PFX_AGENT_DATABASE_URL` as `mysql+asyncmy://<agent-account>:<URL-encoded-password>@<host>:<port>/prizeflux_agent`. Do not reuse the Campaign database account, put credentials in Git, or grant the Agent account access to Campaign tables. The service can start without this setting for liveness diagnostics, but `/health/ready` returns HTTP 503 until the Agent database is reachable and its Alembic head matches the checked-in migration graph; Agent session/run routes also remain unavailable.

Create the schema and separate accounts out of band: the runtime account needs only the required
`SELECT`, `INSERT`, `UPDATE`, and `DELETE` privileges on `prizeflux_agent`; the migration account
needs schema-scoped DDL privileges. Apply versioned migrations explicitly with the migration URL,
then start the service with the runtime URL:

```powershell
$env:PFX_AGENT_MIGRATION_DATABASE_URL = 'mysql+asyncmy://agent_migrator:<URL-encoded-password>@127.0.0.1:3307/prizeflux_agent'
uv run --locked alembic upgrade head
$env:PFX_AGENT_DATABASE_URL = 'mysql+asyncmy://agent_runtime:<URL-encoded-password>@127.0.0.1:3307/prizeflux_agent'
uv run --locked python -m prizeflux_agent
```

The application does not create tables or run migrations at startup. MySQL is the session/run authority; Redis is not used for conversational history. Persisted runs contain the user prompt and final answer, but never the Bearer token, hidden model reasoning, or raw provider error/body. Apply Alembic migrations explicitly before starting the service. The local automated deletion/retention path is implemented, but it has not been validated against production privacy/backup policy or the product UI, so no public Agent API is exposed.

The MySQL 8.4 persistence/API tests are opt-in and start a disposable, no-volume Testcontainers database. In PowerShell, run `$env:PFX_AGENT_RUN_MYSQL_TESTS = '1'` followed by `uv run --locked pytest -m mysql`; the fixture removes its temporary container and credentials after the run. Ordinary `pytest` skips these Docker tests.
