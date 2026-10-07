# Automation Studio — MVP Product Requirements Document (v1.1)

## 1. Product
Automation Studio is a self-hostable, n8n/Activepieces-inspired visual workflow automation platform.
It is **free to run**: open-source stack, and the default LLM is the free Qwen3-Coder endpoint on OpenRouter.

Users can:
- Create workflows visually.
- Connect trigger, logic, AI, HTTP, and output nodes.
- Configure each node.
- Test individual nodes.
- Run an entire workflow.
- See live execution status and logs.
- Save/load workflows.
- Generate a workflow from a natural-language description using an existing LLM.

## 2. MVP Goal
Prove the complete loop:

Natural language / drag-and-drop → workflow JSON → validation → execution engine → node outputs → execution logs.

No model training is required.

## 3. Target User
Developers, students, technical users, and teams who want simple visual automation without writing an entire integration from scratch.

## 4. Core MVP Features

### P0 — Must Have
1. Visual workflow canvas.
2. Node palette.
3. Drag/drop nodes.
4. Connect nodes with edges.
5. Node configuration panel.
6. Save workflow (invalid workflows save as DRAFT).
7. Load workflow.
8. Manual trigger (with optional JSON payload).
9. HTTP Request node.
10. AI/LLM node (text or structured JSON output).
11. Condition/IF node (defined expression grammar).
12. Transform node (declarative mapping; **no arbitrary code in MVP**).
13. Output node.
14. Workflow execution engine (async, parallel branches, defined branch/skip/merge rules).
15. Execution history (with workflow snapshot per run).
16. Per-node status: waiting/running/success/failed/skipped.
17. Input/output inspection (secrets redacted).
18. Test Node (with pinned or last-run upstream data).
19. Error handling: per-node `onError` (stop/continue/route) and retries.
20. Natural-language → workflow generation, with validation repair loop.
21. Credentials UI (write-only, encrypted) — moved up from P1 because HTTP/LLM auth needs it.
22. SSE live updates with replay on reconnect — moved up from P1 because "live execution" is a success criterion.
23. Import/export workflow JSON — moved up from P1 (trivial and makes sharing/demo easy).

### P1 — Nice to Have
- Webhook trigger (requires auth + per-webhook secret first).
- PostgreSQL node.
- Schedule/Cron trigger.
- Retry-from-failed-node.
- Merge and Switch nodes.
- Code node in an isolated sandbox worker.
- Workflow templates.
- Dark/light theme.

### P2 — Later
- Plugin SDK, marketplace, team collaboration, OAuth integrations, version history UI, cloud deployment,
  loops/iteration over arrays, human approval nodes, user accounts/RBAC.

## 5. Node Types

### Trigger
- Manual Trigger (MVP) · Webhook (P1) · Schedule (P1)
### Data/API
- HTTP Request (MVP) · Transform (MVP) · Code (P1, sandboxed)
### AI
- LLM (MVP) · AI Extract / AI Classify (presets of LLM with JSON output)
### Logic
- IF / Condition (MVP) · Switch (P1) · Merge (P1)
### Storage
- PostgreSQL (P1) · File/JSON (P2)
### Output
- Output (MVP) · Webhook Response (P1) · Notification (P2)

## 6. AI Workflow Builder

User enters: "Get weather data, ask AI whether I need an umbrella, and show the answer."

The LLM must return strict JSON matching the workflow schema. The backend MUST:
1. Parse JSON (tolerating code fences).
2. Validate node types.
3. Validate required configuration.
4. Validate edges, references, and expressions.
5. Reject unsupported operations.
6. On errors, send them back to the LLM for up to 2 repair rounds.
7. Save a fully valid result as `VALID`; if still invalid, save as `DRAFT` with an issues list. A DRAFT cannot be run.

Never allow the LLM to directly execute arbitrary shell commands or unrestricted code.

## 7. Execution UX

When the user presses Run:
1. Validate; reject if DRAFT.
2. Create execution record with a snapshot of the workflow definition.
3. Return immediately (202); run on a bounded worker pool.
4. Resolve the graph from the trigger; run ready nodes (branches in parallel).
5. Store input/output per node (redacted, size-capped).
6. Stream status to the frontend over SSE (reconnect replays missed events).
7. Apply each node's `onError` and retry settings.
8. Mark execution SUCCESS/FAILED/CANCELLED; allow cancel at any time.
9. Allow inspecting every node, including skipped branches.

Example:

✓ Manual Trigger       8 ms
✓ HTTP Request       320 ms
✓ AI Analysis         1.8 s
✓ Condition             4 ms
✓ Output                3 ms
– Other Output     skipped

## 8. Non-Goals for MVP
- Full n8n feature parity · hundreds of integrations · multi-region cloud · model training
- Enterprise RBAC · billing/subscriptions · user accounts
- Production-grade arbitrary code execution

## 9. Success Criteria
A user can: open the app; drag nodes; connect them; configure them; save; test a node; run the workflow;
watch live execution; inspect outputs; generate a similar workflow with natural language.

## 10. Demo Workflow
Manual Trigger → HTTP Request (Open-Meteo, no API key) → LLM (JSON answer) → IF → Output A / Output B.
Prompt for the generator: "Fetch data from an API, analyze it, and return a useful summary."
All example workflows use real, keyless public APIs so the demo works as shipped.

## 11. Cost and Limits ("free, no limits for now")
- Software: all open source; no licence fees. No app-side caps on workflows, nodes, or executions.
- LLM: a **routing chain**: Qwen3-Coder (OpenRouter free) first; then other free OpenRouter models; then other
  providers' free tiers; finally a local model (unlimited). See `docs/LLM_ROUTING.md`.
- Honest limit: hosted free tiers are rate-limited by their providers (OpenRouter free: 20 req/min, 50/day, or
  1,000/day after a one-time $10 credit purchase, shared across all its free models). The only truly unlimited tier is
  the local model, whose speed/quality depend on your hardware.
- The app rotates automatically, returns to Qwen when its limit resets, shows which model answered, and fails with
  `LLM_ALL_TIERS_EXHAUSTED` (with reset times) only if every tier is unavailable.
- App-side timeouts/size limits are generous defaults in `.env`. Safety guards (SSRF block, redaction) stay on.

## 12. Security Requirements
- Never expose provider API keys or stored secrets to the browser; credentials are write-only.
- Encrypt stored secrets (AES-256-GCM).
- Allowlist node types; validate workflow JSON server-side.
- Redact secrets from persisted inputs and logs.
- No arbitrary generated code in the main backend process.
- Execution timeouts, request/response size limits.
- **SSRF protection on by default from day one** (not "before production").
- Prompt-injection hygiene for LLM nodes (untrusted-data delimiters; LLM output can't change the graph).
- Local services bind to 127.0.0.1; add auth before any webhook trigger or shared deployment.

## 13. MVP Definition of Done
Works locally with: React frontend; Spring Boot backend; PostgreSQL; an LLM routing chain
(OpenRouter free Qwen3-Coder primary, fallbacks, local Ollama final); at least 5 working node types
(manual trigger, HTTP, LLM, condition, transform, output); persistent workflows; persistent execution history;
live execution status; the three bundled example workflows run end-to-end.
