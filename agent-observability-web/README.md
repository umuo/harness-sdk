# Agent Observatory

Standalone Next.js receiver and dashboard for Agent SDK trace schema versions
`1`, `2`, and `3`. See [`docs/observability-platform.md`](../docs/observability-platform.md)
for setup, Java integration, configuration, storage limits, and security.
The dashboard supports Chinese and English and defaults to Chinese. It also
provides application CRUD, per-application ingestion Keys, Key rotation, and
application-scoped Trace filtering. Version 3 traces include a clickable
Task/Turn/Step/Model/Tool call graph with raw Provider payloads and normalized
SDK request and response details. The dashboard groups a root Agent and all
Agent-as-Tool descendants into one caller-triggered Task while retaining the
original per-Turn trace documents. Administrators can delete one Task or
select multiple Tasks; deletion includes every Turn segment in each Task.

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
