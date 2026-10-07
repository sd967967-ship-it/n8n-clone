# MVP Workflow Examples (all runnable as shipped; keyless public APIs)

## 1. HTTP → AI → Output  (`workflows/api-summary.json`)
Manual Trigger → HTTP Request (JSONPlaceholder posts) → LLM summary → Output.

## 2. HTTP/Trigger → Condition → Output A / B  (`workflows/condition-demo.json`)
Manual Trigger carries `{"score": 72}` → Condition `trigger_1.output.score >= 50` → "Passed" / "Failed".
Only the matching branch runs; the other Output shows as SKIPPED.

## 3. AI Classification  (`workflows/classifier.json`)
Manual Trigger (sample ticket) → LLM (JSON: `{"priority":"high|low","reason":"…"}`) → Condition → Alert / Normal.

## 4. Weather + umbrella  (`workflows/weather-ai.json`)
Manual Trigger → HTTP Request (Open-Meteo) → LLM (JSON `needs_umbrella`) → Condition → Output A / B.

## 5. AI Workflow Generator
User: "Call an API, summarize the result, and show the answer."
LLM returns JSON → backend validates (≤2 repair rounds) → saved VALID or DRAFT → canvas renders it.

## Demo Script
1. Import `weather-ai.json` (or create a blank workflow).
2. Show the canvas: Trigger → HTTP → LLM → Condition → two Outputs.
3. Click **Test Node** on HTTP Request; pin its output.
4. Click **Test Node** on the LLM; it uses the pinned data (and the response is cached, so retesting is instant).
5. Click **Run Workflow**; watch statuses go live; one Output shows SKIPPED.
6. Open the execution log; inspect any node's input/output.
7. Break it on purpose (bad URL) with `onError: route` to show error handling.
8. Ask the AI Builder: "Fetch posts from an API and summarize them." Show the generated graph (and a DRAFT with highlighted nodes if something's missing).
9. Fix, validate, run.
