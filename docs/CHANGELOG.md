# Changelog

## v1.2: LLM routing chain
- Qwen3-Coder first, then a rotating fallback chain, with a local Ollama model as the unlimited final tier.
- Chain is data: `config/llm-routing.yml`. Tiers without API keys are skipped.
- Found and designed around the fact that OpenRouter's free daily cap is account-wide across all `:free` models,
  so the chain also spans other providers (Google, Groq, Cerebras) with separate quotas.
- Per-tier states (COOLDOWN / EXHAUSTED / CIRCUIT_OPEN), automatic return to Qwen after reset, soft-fail on invalid JSON.
- Transparency: model/tier recorded per node, `llm.failover` SSE event, `GET /api/llm/status`, `routing: "local_only"`.
- Planned (P1): startup catalog check and an eval harness that orders tiers by workflow-JSON validity.
- New: `docs/LLM_ROUTING.md`, `config/llm-routing.yml`; updated `.env.example`, compose, PRD §11, ARCH §3, schema `llm` node, README.

## v1.1: design gap fixes + free LLM

## Free / no-cost setup
- Default LLM → OpenRouter `qwen/qwen3-coder:free` via the OpenAI-compatible API; swap by env only.
- Throttle + retry + fallback models + response cache so the provider's free-tier rate limit rarely surfaces.
- Ollama moved to an optional `local-llm` compose profile.
- Provider-side free limits cannot be removed; documented in README, PRD §11, ARCHITECTURE §3.

## Gap → resolution
| # | Gap | Resolution | Where |
|---|---|---|---|
| 1 | Condition expressions contradicted "no expressions" | Defined grammar + allowed functions, one ref style, no `eval` | SCHEMA |
| 2 | Code node vs. no-code-in-process rule | MVP Transform is declarative; Code node is P1 in an isolated sandbox | PRD, SCHEMA, ARCH §10 |
| 3 | Validate-then-save vs. AI output with blanks | DRAFT/VALID status + 2-round repair loop | PRD §6, ARCH §11 |
| 4 | Branch/join semantics | Delivered/dead edges, SKIPPED propagation, merge modes | SCHEMA |
| 5 | Per-node error settings | `settings.onError/retries/timeoutMs` | SCHEMA |
| 6 | LLM free text driving conditions | `outputFormat: json` + `jsonShape`; LLM output shape defined | SCHEMA |
| 7 | Test Node had no input | Manual input > `pinData` > last execution | SCHEMA, ARCH §8 |
| 8 | History rewritten by edits | Definition snapshot per execution; workflow `version` + optimistic locking | ARCH §7 |
| 9 | Secrets in logs; no credential refs | `{{credentials.X}}` (restricted fields), AES-GCM, redaction | SCHEMA, ARCH §10 |
| 10 | Unbounded stored output | Size cap + truncated flag + retention job | ARCH §7, §10 |
| 11 | Reference safety | Immutable IDs; validator checks refs/upstream; UI rewrites on duplicate | SCHEMA, ARCH §12 |
| 12 | Execution model undefined | Async 202, bounded pool, cancel interrupts calls, crash recovery, SSE replay | ARCH §5, §9 |
| 13 | SSRF deferred | On by default, DNS + redirect checks | ARCH §10 |
| 14 | Prompt injection | Untrusted-data delimiters; LLM output cannot alter graph | ARCH §10 |
| – | Compose lacked services/healthcheck | Added healthcheck, app profile, localhost bindings | docker-compose.yml |
| – | Demo hit example.com | Real keyless APIs (Open-Meteo, JSONPlaceholder) | workflows/ |
| – | Output node undefined | `config.value` template; stored as execution result | SCHEMA |
| – | Missing `classifier.json`, `active` flag | Added | workflows/, ARCH §7 |
| – | No auth | Documented single-user local mode; auth required before webhooks | ARCH §10 |

## Unchanged
Stack (React/React Flow, Spring Boot, PostgreSQL), module layout, API shape, build order, demo flow.
