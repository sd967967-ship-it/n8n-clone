# sandbox-worker (P1)

Isolated Code-node runner. MVP Transform is declarative (no code); the Code node
arrives in P1 and runs here — never inside the API process.

Planned: no network, no filesystem, CPU/memory/time caps, `{ result }` output shape.
