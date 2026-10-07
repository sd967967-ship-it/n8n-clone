# Workflow JSON Schema — MVP (v1.1)

A workflow = metadata + nodes + edges (+ optional pinned data).

## Example

```json
{
  "version": 1,
  "name": "Weather AI Assistant",
  "description": "Fetch weather and decide whether an umbrella is needed",
  "nodes": [
    { "id": "trigger_1", "type": "manual_trigger", "name": "Start",
      "position": { "x": 100, "y": 200 }, "config": { "payload": {} } },
    { "id": "http_1", "type": "http_request", "name": "Get Weather",
      "position": { "x": 350, "y": 200 },
      "config": { "method": "GET",
        "url": "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m,precipitation&daily=precipitation_probability_max&timezone=auto" },
      "settings": { "retries": 2, "timeoutMs": 30000, "onError": "stop" } },
    { "id": "llm_1", "type": "llm", "name": "Analyze Weather",
      "position": { "x": 650, "y": 200 },
      "config": {
        "model": "default",
        "prompt": "Given this weather data, do I need an umbrella today?\n{{http_1.output.body}}",
        "outputFormat": "json",
        "jsonShape": { "needs_umbrella": "boolean", "reason": "string" } } },
    { "id": "condition_1", "type": "condition", "name": "Umbrella?",
      "position": { "x": 950, "y": 200 },
      "config": { "expression": "llm_1.output.json.needs_umbrella == true" } },
    { "id": "output_yes", "type": "output", "name": "Take umbrella",
      "position": { "x": 1250, "y": 100 },
      "config": { "value": "Take an umbrella. {{llm_1.output.json.reason}}" } },
    { "id": "output_no", "type": "output", "name": "No umbrella",
      "position": { "x": 1250, "y": 300 },
      "config": { "value": "No umbrella needed. {{llm_1.output.json.reason}}" } }
  ],
  "edges": [
    { "id": "e1", "source": "trigger_1",   "target": "http_1" },
    { "id": "e2", "source": "http_1",      "target": "llm_1" },
    { "id": "e3", "source": "llm_1",       "target": "condition_1" },
    { "id": "e4", "source": "condition_1", "target": "output_yes", "sourceHandle": "true" },
    { "id": "e5", "source": "condition_1", "target": "output_no",  "sourceHandle": "false" }
  ],
  "pinData": {}
}
```

`model: "default"` means "use the routing chain" (`config/llm-routing.yml`: Qwen first, then fallbacks, local last).
Provider URLs and API keys are **never** stored in workflow JSON. `routing: "local_only"` forces the local tier.

## Node envelope

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Unique, immutable, `[a-z0-9_]+`. References use IDs, so renaming `name` never breaks anything. |
| `type` | yes | Must be in the allowlist (below). |
| `name` | yes | Display label, editable. |
| `position` | yes | `{x, y}` for the canvas. |
| `config` | yes | Type-specific (below). |
| `settings` | no | `onError`, `retries`, `retryDelayMs`, `timeoutMs`. |

### `settings`

```json
{ "onError": "stop", "retries": 0, "retryDelayMs": 1000, "timeoutMs": 60000 }
```

- `onError: "stop"` (default): fail the execution; unrun nodes become SKIPPED.
- `onError: "continue"`: node is marked FAILED, output becomes `{ "error": { "message": "..." } }`, downstream nodes still run.
- `onError: "route"`: failure is sent down edges whose `sourceHandle` is `"error"`; success uses the default handle.
- `retries` 0–5, fixed delay. Each attempt is recorded.

## Edges

`{ id, source, target, sourceHandle? }`. `sourceHandle` is only meaningful for
`condition` (`"true"`/`"false"`), `switch` (case name), and `onError: route` (`"error"`).
Edges without a handle are the node's normal output.

## Node catalog and config

| type | Phase | Required config | Output shape |
|---|---|---|---|
| `manual_trigger` | MVP | none; optional `payload` (JSON) | the `payload` (or `{}`) |
| `http_request` | MVP | `method`, `url`; optional `headers`, `query`, `body`, `auth` | `{ status, headers, body }` (body parsed as JSON if possible, else string) |
| `llm` | MVP | `prompt`; optional `system`, `model`, `temperature`, `outputFormat` (`text`\|`json`), `jsonShape`, `maxInputChars`, `routing` (`auto`\|`local_only`) | `{ text, json?, model, tier, provider, attempts[], usage }` |
| `condition` | MVP | `expression` | pass-through of input; routes via handle |
| `transform` | MVP | `mapping` (object of output-field → reference or template) | the mapped object |
| `output` | MVP | optional `value` (template); default = whole input | `{ value }`; also stored as the execution `result` |
| `merge` | P1 | `mode`: `wait_all` \| `first` | object keyed by parent id |
| `switch` | P1 | `value`, `cases[]` | pass-through; routes by case |
| `webhook_trigger`, `schedule_trigger` | P1 | see PRD | request / tick payload |
| `postgres` | P1 | `credential`, `query`, `params` | `{ rows }` |
| `code` | P1 | `language: "js"`, `source` — runs **only** in the isolated sandbox worker, never in the API process | `{ result }` |

AI Extract / AI Classify are presets of `llm` with `outputFormat: "json"`.

### `http_request` details

```json
{ "method": "POST", "url": "https://api.example.com/v1/items",
  "headers": { "Authorization": "Bearer {{credentials.my_api}}" },
  "query": { "q": "{{trigger_1.output.term}}" },
  "body": { "name": "{{trigger_1.output.name}}" } }
```

`{{credentials.NAME}}` is allowed **only** in `headers`, `auth`, and `query` of
`http_request`, and in `postgres.credential`. It is resolved at call time, in memory.

### `transform` (declarative, no code)

```json
{ "mapping": {
    "temp": "http_1.output.body.current.temperature_2m",
    "label": "Temp is {{http_1.output.body.current.temperature_2m}}°C"
} }
```

A value that is a bare reference copies the typed value. A value containing `{{ }}` renders a string.

## Variable syntax (one rule, two places)

1. **Templates** in string config fields: `{{ref}}` where `ref` is a path:
   `{{http_1.output.body.items[0].name}}`. Objects/arrays are inserted as compact JSON.
2. **Expressions** (only `condition.expression`, `switch.value`): bare refs, no braces.

`ref` roots:

| Root | Meaning |
|---|---|
| `<node_id>.output...` | Output of any node **upstream** of this one. |
| `input...` | Alias for this node's input: the single parent's output, or (multiple parents) an object keyed by parent id. |
| `credentials.NAME` | Stored secret (restricted fields only, see above). |
| `execution.id`, `execution.startedAt` | Execution metadata. |

Missing path → `null` (templates render empty string and log a warning). References to a node that is
not upstream are a **validation error**.

## Expression grammar (not arbitrary code)

```
expr     := or
or       := and ( "||" and )*
and      := not ( "&&" not )*
not      := "!" not | cmp
cmp      := value ( ("=="|"!="|"<"|"<="|">"|">="|"in") value )?
value    := literal | ref | call | "(" expr ")"
literal  := string('..' or ".."), number, true, false, null
call     := name "(" [expr ("," expr)*] ")"
```

Allowed functions: `contains(a,b)`, `startsWith`, `endsWith`, `lower`, `upper`, `trim`,
`length`, `isEmpty`, `number`, `string`, `exists(ref)`.
No arithmetic, no assignment, no loops, no method calls, no reflection. Parsed by a hand-written
parser into an AST and interpreted; nothing is ever passed to `eval`.
Type rules: comparing mismatched types is `false` (not an error); `<`/`>` require numbers or numeric strings.

## Execution semantics

- **Graph**: must be a DAG with exactly one trigger. Cycles are rejected in MVP.
- **Runnable**: a node runs when every incoming edge is *resolved*. An edge is *delivered* if its source
  succeeded and (if conditional) took that handle; otherwise it is *dead*.
- **Run vs skip**: runs if at least one incoming edge is delivered; if all are dead → `SKIPPED`
  (and its outgoing edges are dead, so skips propagate).
- **Merge**: `wait_all` waits for all live parents; `first` fires on the first delivered edge and ignores later ones.
- **Parallel branches** run concurrently up to `ENGINE_MAX_CONCURRENT_EXECUTIONS` workers.
- **Workflow result** = output of the last `output` node(s) that ran.

## Pinned data (for Test Node)

`pinData` maps `node_id → output JSON`. When testing a node, upstream values come from, in order:
1. request-supplied manual input, 2. `pinData`, 3. that node's output from the latest successful execution.
If none exist, the test returns `MISSING_UPSTREAM_DATA` listing which nodes to pin or run.

## Validation

Errors have a stable `code`, `nodeId?`, `path?`, `message`.

| Code | Rule |
|---|---|
| `UNKNOWN_NODE_TYPE` | Type not in allowlist |
| `MISSING_NODE_ID` / `DUPLICATE_NODE_ID` | ID rules |
| `EDGE_UNKNOWN_NODE` | Edge source/target doesn't exist |
| `EDGE_BAD_HANDLE` | Handle not valid for source type |
| `CYCLE_DETECTED` | Graph is not a DAG |
| `TRIGGER_COUNT` | Not exactly one trigger |
| `MISSING_CONFIG` | Required config absent/blank |
| `BAD_EXPRESSION` | Expression fails to parse |
| `REF_UNKNOWN_NODE` / `REF_NOT_UPSTREAM` | Bad reference |
| `CREDENTIAL_NOT_ALLOWED` / `CREDENTIAL_NOT_FOUND` | Credential misuse |
| `URL_BLOCKED` | Static URL fails the SSRF guard |
| `UNREACHABLE_NODE` | Node can't be reached from the trigger (warning) |

Workflow validity: `VALID` (no errors) or `DRAFT` (has errors, saved but not runnable).
Warnings never block a run.
