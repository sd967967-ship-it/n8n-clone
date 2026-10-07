# Automation Studio — MVP Architecture (v1.1)

## 1. High-Level Architecture

```text
                    Browser
                       |
                       v
              React + TypeScript
                       |
          REST  +  SSE (with replay)
                       |
                       v
              Spring Boot API
                 /           \
                v             v
       Workflow Service   Execution Service ──> bounded worker pool
               |               |
               v               v
          PostgreSQL      Node Executors
                               |
        +---------+---------+--+--------+-----------+
        v         v         v           v           v
      HTTP       LLM       Logic        DB        Code (P1)
      Node       Node      Nodes       Node     isolated sandbox
        |         |
   SSRF guard     v
              LlmRouter (tiers · cooldowns · quotas · cache)
                  |
                  v
   1 OpenRouter qwen/qwen3-coder:free   (primary)
   2 other OpenRouter :free models      (same account quota)
   3 Google / Groq / Cerebras free tiers (separate quotas)
   4 local Ollama                       (final, unlimited)
```

## 2. Stack

Frontend: React, TypeScript, Vite, React Flow, Tailwind CSS, shadcn/ui, Zustand, TanStack Query, Monaco Editor.

Backend: Java 21, Spring Boot, Spring Web, Spring Validation, Spring Data JPA, Flyway, PostgreSQL 16 (`jsonb`), Jackson, SSE.

AI: any OpenAI-compatible endpoint via a routing chain. **Primary: OpenRouter `qwen/qwen3-coder:free`; final fallback: local Ollama.**

## 3. Free LLM strategy: Qwen first, rotating fallbacks, local last

Default primary: OpenRouter `qwen/qwen3-coder:free`. Then the router fails over, in order, through other free
OpenRouter models, other providers' free tiers, and finally a local Ollama model (unlimited).
Full design: `docs/LLM_ROUTING.md`; chain config: `config/llm-routing.yml`.

Key facts that shaped the design:
- OpenRouter's free cap (20 req/min; 50/day, or 1,000/day after a one-time $10 credit purchase) is **account-wide
  across all `:free` models**. Rotating between OpenRouter free models only helps with upstream congestion, not the daily cap.
  The chain therefore spans multiple providers (separate quotas) before falling to local.
- Free catalogs change often; the chain is data (YAML), not code, and tiers without keys are skipped.

`LlmRouter` (in `ai/`) per call:
1. Start at the highest-priority tier that is AVAILABLE (so Qwen is retried as soon as its limit resets).
2. Skip tiers lacking a key, capability (json/tools), or enough context.
3. On 429/quota → mark COOLDOWN or EXHAUSTED (provider-wide or per-model) and **immediately** try the next tier.
4. On 5xx/timeout → circuit breaker; on invalid JSON → soft-fail to next tier.
5. Local Ollama is the last tier; if even that is down → `LLM_ALL_TIERS_EXHAUSTED` with next-reset times.

Also: response cache (identical prompts cost nothing), per-provider token buckets, `routing: "local_only"` per node,
`llm.failover` SSE events, and `GET /api/llm/status`.
Privacy: free hosted endpoints may log prompts. Use `local_only` for private data.

## 4. Backend Modules

```text
com.automationstudio
├── api            WorkflowController, ExecutionController, AiController, NodeController, CredentialController
├── workflow       WorkflowService, WorkflowValidator, WorkflowRepository, WorkflowMapper
├── execution      ExecutionService, ExecutionEngine, ExecutionContext, ExecutionRepository,
│                  ExecutionEventPublisher, GraphScheduler, RecoveryOnStartup
├── expression     ExpressionParser, ExpressionEvaluator, TemplateRenderer, RefResolver
├── nodes          NodeExecutor, ManualTrigger, Http, Llm, Condition, Transform, Output
├── ai             WorkflowGenerationService, LlmRouter, TierStateStore, QuotaTracker, LlmCache, WorkflowPrompt
├── security       SsrfGuard, SecretsService (AES-GCM), Redactor
└── config
```

## 5. Execution Engine

```text
POST /execute ──> 202 {executionId}      (never blocks the request thread)
                      |
                      v
   snapshot workflow definition + version into the execution row
                      |
                      v
   validate ──> status QUEUED ──> worker pool picks it up ──> RUNNING
                      |
                      v
   GraphScheduler: resolve edges (delivered/dead) → run ready nodes in parallel
                      |
        per node: render templates → [SSRF check] → execute (timeout, retries)
                  → redact → store input/output → publish event
                      |
   onError: stop | continue | route        SKIPPED propagates over dead edges
                      |
                      v
   SUCCESS | FAILED | CANCELLED, finished_at, result = output node value(s)
```

Node input/output contract:

```json
// executor receives
{ "config": {}, "input": {}, "context": { "executionId": "…", "outputs": { "node_id": {} } } }
// executor returns
{ "status": "SUCCESS", "output": {}, "metadata": { "durationMs": 0, "attempt": 1 } }
```

Cancellation: cooperative flag checked between nodes **plus** interruption of the in-flight call
(HTTP and LLM calls use cancellable async clients). Cancelled nodes → `CANCELLED`, pending → `SKIPPED`.

Crash recovery: on startup, executions left in `RUNNING`/`QUEUED` are marked `FAILED`
with `error_message = "Server restarted during execution"`.

Whole-workflow wall-clock cap: `WORKFLOW_MAX_DURATION_MS` (default 15 min).

## 6. Status values

Workflow execution: `QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED`
Node execution: `WAITING, RUNNING, SUCCESS, FAILED, SKIPPED, CANCELLED`
UI mapping to the PRD's four states: queued = WAITING, running, success, failed (SKIPPED/CANCELLED shown greyed).

## 7. Database (Flyway migrations, `jsonb` for JSON)

### workflows
`id, name, description, definition_json, status (DRAFT|VALID), version int, active bool, created_at, updated_at`
`PUT` requires the current `version` (optimistic locking); mismatch → 409.

### executions
`id, workflow_id, workflow_version, definition_snapshot_json, status, trigger_type, trigger_payload_json, result_json, started_at, finished_at, error_message`

### execution_nodes
`id, execution_id, node_id, attempt, status, input_json, output_json, output_size_bytes, truncated bool, error_message, duration_ms, started_at, finished_at`

### execution_events  (SSE replay)
`id, execution_id, seq int, type, node_id, payload_json, created_at`  — `UNIQUE(execution_id, seq)`

### llm_tier_state
`tier_id, state, until, used_today, day_utc` (survives restarts)

### credentials
`id, name (unique), provider, encrypted_value, nonce, created_at`
Encrypted with AES-256-GCM using `SECRETS_KEY`. Values are write-only through the API.

Indexes: `executions(workflow_id, started_at desc)`, `execution_nodes(execution_id)`, `execution_events(execution_id, seq)`.
Retention job deletes executions older than `EXECUTION_RETENTION_DAYS`.

## 8. API

### Workflows
```
POST   /api/workflows                 create (saves DRAFT if invalid; returns issues[])
GET    /api/workflows
GET    /api/workflows/{id}
PUT    /api/workflows/{id}            body includes version; 409 on conflict
DELETE /api/workflows/{id}
POST   /api/workflows/{id}/validate   -> { status, errors[], warnings[] }
```
### Execution
```
POST   /api/workflows/{id}/execute    -> 202 { executionId }   (409 if workflow is DRAFT)
GET    /api/executions?workflowId=    list
GET    /api/executions/{id}           status + nodes + result
GET    /api/executions/{id}/events    SSE; supports Last-Event-ID replay from execution_events
POST   /api/executions/{id}/cancel
POST   /api/executions/{id}/retry     re-run from a failed node (P1)
```
### AI
```
POST   /api/ai/generate-workflow      { prompt } -> { workflow, status, issues[] }
```
### Node testing
```
POST   /api/nodes/test
  { "node": {...}, "workflowId": "…?", "input": {…}? , "useLastExecution": true? }
```
Upstream values: manual `input` > `pinData` > last successful execution (see schema doc).
### LLM
```
GET /api/llm/status   tier states, estimated remaining requests, next resets
```
### Credentials
```
POST /api/credentials   { name, provider, value }   GET list (names only)   DELETE
```
`GET` never returns values.

## 9. SSE events

`execution.started`, `node.started`, `node.succeeded`, `node.failed`, `node.skipped`,
`execution.finished`, `llm.failover`. Each has a monotonically increasing `id` (= `seq`). A client that reconnects
with `Last-Event-ID` is replayed everything after it, so a page refresh never loses the log.
Heartbeat comment every 15 s.

## 10. Security

- **Allowlisted node types**; server validates every workflow; the browser is never trusted.
- **SSRF guard (on by default, even locally)**: resolve DNS first, reject loopback, private (RFC1918),
  link-local (incl. 169.254.169.254), CGNAT, multicast, and IPv6 equivalents; re-check after every redirect;
  cap redirects at 5; only `http`/`https`. Override only via `HTTP_ALLOW_PRIVATE=true`.
- **Secrets**: AES-GCM at rest, referenced as `{{credentials.NAME}}`, resolved in memory at call time,
  allowed only in specific fields, and **never** interpolated into LLM prompts (validator rejects it).
- **Redaction**: before persisting `input_json`/logs, credential fields are stored as templates
  (`{{credentials.x}}`) and headers `Authorization`, `Cookie`, `X-API-Key`, `Proxy-Authorization` are masked.
- **Limits** (generous, configurable): response size, stored output size (truncated flag set, full value
  stays in memory for downstream nodes), per-node and whole-workflow timeouts, request body size.
- **Prompt injection**: the LLM node wraps interpolated data in `<untrusted_data>` delimiters with a system
  instruction to treat it as data only. LLM output can only be *read* by conditions/transform; it can never
  create nodes, choose node types, or trigger execution at runtime. Generated *workflows* are always
  re-validated server-side.
- **Code node (P1)**: runs in a separate sandbox worker (no network, no filesystem, CPU/memory/time caps),
  never inside the API process.
- **Auth**: single-user local mode for MVP (no login). Services bind to `127.0.0.1`. Before any webhook trigger
  or shared deployment: add authentication and per-webhook secrets.

## 11. AI Workflow Generation

```text
prompt ──> WorkflowPrompt (node catalog + config schemas + 1 example + rules)
        ──> LlmClient (JSON mode if supported, else extract JSON from text)
        ──> parse ──> WorkflowValidator
              ├─ valid ──> save VALID
              └─ errors ──> feed errors back to LLM (max 2 repair rounds)
                              ├─ valid ──> save VALID
                              └─ still invalid ──> save DRAFT + issues[] (user fixes in UI)
```

Rules baked into the prompt: only catalog node types; use `{{credentials.X}}` placeholders instead of keys;
leave unknown values blank rather than inventing URLs; prefer `llm` with `outputFormat: json` before a `condition`.
Missing config is expected: the workflow lands as DRAFT, opens on the canvas with the problem nodes highlighted.

## 12. Frontend State

```text
workflowStore:  nodes, edges, selectedNode, metadata, version, validationIssues, pinData
executionStore: executionId, workflowStatus, nodeStatuses, nodeOutputs, logs, lastEventId
```
Duplicate/paste of nodes generates new IDs and rewrites internal `{{refs}}`. Deleting a referenced node shows a warning.

## 13. Deployment

```text
docker compose up -d                       # PostgreSQL
docker compose --profile app up -d --build # + backend + frontend (once they exist)
docker compose --profile local-llm up -d   # + optional Ollama
```
Everything runs on one laptop; the LLM runs remotely (free) unless you opt into Ollama.
