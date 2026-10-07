# Recommended Repository Structure

```text
automation-studio/
├── frontend/
│   ├── src/
│   │   ├── components/ {canvas, nodes, sidebar, inspector, execution, credentials}
│   │   ├── stores/  api/  types/  pages/  App.tsx
│   └── package.json
├── backend/
│   ├── src/main/java/com/automationstudio/
│   │   ├── api/  workflow/  execution/  expression/  nodes/  ai/  security/  config/
│   ├── src/main/resources/ {application.yml, db/migration/ (Flyway V1__init.sql …)}
│   ├── src/test/java/…     (validator, expression parser, scheduler, SSRF guard tests)
│   └── pom.xml
├── sandbox-worker/          (P1: isolated Code-node runner)
├── workflows/ {weather-ai.json, api-summary.json, condition-demo.json, classifier.json}
├── docs/ {PRD.md, ARCHITECTURE.md, WORKFLOW_SCHEMA.md, WORKFLOW_EXAMPLES.md, FOLDER_STRUCTURE.md}
├── docker-compose.yml  README.md  CHANGELOG.md  .env.example
```

## Build Order
1. Frontend canvas + node palette.
2. Workflow JSON + save/load/import/export.
3. Spring Boot workflow API + Flyway schema + **validator and expression parser (with unit tests)**.
4. Execution engine: scheduler (delivered/dead edges), async pool, snapshot, recovery, cancel.
5. HTTP (+ SSRF guard), Condition, Transform, Output, LLM (+ throttle/retry/fallback/cache).
6. SSE events with replay; credentials + redaction.
7. AI workflow generator with repair loop and DRAFT state.
8. Test Node (pinned/last-run data), polish, templates, demo.

Tip: do step 3's validator and expression parser first as pure-Java libraries with tests; every later phase depends on them.
