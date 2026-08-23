# Agent Observatory

Standalone Next.js receiver and dashboard for Agent SDK trace schema versions
`1` and `2`. See [`docs/observability-platform.md`](../docs/observability-platform.md)
for setup, Java integration, configuration, storage limits, and security.
The dashboard supports Chinese and English and defaults to Chinese. It also
provides application CRUD, per-application ingestion Keys, Key rotation, and
application-scoped Trace filtering. Version 2 traces include a clickable
Turn/Step/Model/Tool call graph with structured request and response details.

Requires Node.js 20.9 or newer.

```bash
npm ci
npm run dev
```

Open `http://localhost:3000/applications` to create an application and generate
its one-time ingestion Key. For production, protect management with an
administrator Key:

```bash
npm run --silent generate-key
# Save the result as AGENT_OBSERVABILITY_ADMIN_KEY in .env.local.
```
