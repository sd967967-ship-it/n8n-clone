# Automation Studio — self-hostable visual workflow automation

n8n-inspired MVP: React Flow canvas, Spring Boot engine, PostgreSQL, free LLM routing
(OpenRouter `qwen/qwen3-coder:free` first, local Ollama last). Dark-only UI.

## Status

Phase 1 done: expression grammar + workflow validator as pure Java with 20 unit tests
(`mvn test` green), 4 bundled keyless demo workflows validated by tests.
Frontend canvas scaffold + Playwright smoke test included.

## Prerequisites

- JDK 21+ (tested with 25), Maven 3.9+
- Node.js 22+ (frontend + Playwright)
- PostgreSQL 16 (`jsonb`); `docker compose up -d` runs it on `127.0.0.1:5432`
- Optional: OpenRouter API key (free `:free` models), Ollama for the local final tier

## Quick start

```bash
cp .env.example .env   # set SECRETS_KEY + OPENROUTER_API_KEY; never commit .env
docker compose up -d             # postgres
cd backend && mvn test           # validator + expression unit tests
cd frontend && npm install && npm run dev   # canvas on http://127.0.0.1:5173
```

Playwright smoke: `cd frontend && npx playwright install chromium && npm run test:e2e`.

## Docs

`docs/` holds PRD, architecture, workflow schema, LLM routing, examples, changelog.
`workflows/` holds 4 runnable keyless demos (Open-Meteo, JSONPlaceholder).
`config/llm-routing.yml` is the data-driven LLM tier chain (OpenRouter free + Ollama;
Google/Groq/Cerebras tiers are skipped without keys).

## Safety defaults (locked)

Node 60s / HTTP 10s connect + 30s read / LLM 180s / workflow 15min / SSE heartbeat 15s.
SSRF guard on by default. Secrets AES-256-GCM in `.env`, write-only API, redacted logs,
never interpolated into LLM prompts.
