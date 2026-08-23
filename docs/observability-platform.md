# Observability Web Platform

## Purpose and boundary

`agent-observability-web` is a standalone Next.js service for the SDK's
versioned `AgentTrace` documents. It provides a useful local and single-node
MVP without turning `agent-core` into a monitoring server or adding Java web
framework dependencies.

The first version includes:

- `POST /api/traces` ingestion with schema validation, optional Bearer
  authentication, and a 2 MiB body limit;
- `GET /api/traces` and `GET /api/traces/{turnId}` query endpoints;
- application CRUD and one-time API Key generation/rotation under
  `/api/applications`;
- `GET /api/health` readiness information;
- an overview grouped by caller-triggered Task, with success rate, P95
  duration, Tokens, Tool errors, application/status/Agent filtering, and
  manual/automatic refresh;
- Chinese and English interfaces with Chinese selected by default and the
  browser choice persisted locally;
- a Task detail page with aggregated usage, all participating Agents,
  correlation fields, a merged Parent/SubAgent call graph, clickable node
  request and response inspectors, separate Provider/SDK payload tabs, and a
  Task/Turn/Step/Model/Tool span waterfall;
- individual and multi-select Task deletion from the dashboard, including all
  Turn trace segments belonging to each selected Task;
- atomic local-file persistence behind a small `TraceStore` interface.

The web project is intentionally outside the Maven reactor. Java 8 remains the
SDK runtime requirement; the dashboard uses Node.js 20.9 or newer.

## Run locally

```bash
cd agent-observability-web
npm ci
npm run dev
```

Open `http://localhost:3000/applications`, create an application, and save the
API Key shown by the platform. The plaintext is displayed only once. Then
register platform observability on the Java Agent:

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);

Agent agent = Agent.builder()
    .name("assistant")
    .model(model)
    .plugin(observability)
    .build();
```

Close `observability` when the application shuts down so its asynchronous
queue can drain.

## Applications and ingestion keys

Each registered application has one independently generated ingestion Key.
Creating or rotating an application produces an `aoh_...` Bearer secret backed
by 32 cryptographically secure random bytes. The platform stores only its
SHA-256 hash and a short display hint; the plaintext cannot be recovered after
the one-time dialog is closed.

Application lifecycle behavior is deliberately predictable:

- creating the first application disables anonymous Trace ingestion;
- rotating a Key invalidates the previous Key immediately;
- deleting an application invalidates its Key immediately;
- deleting does not cascade into historical Trace deletion;
- each accepted Trace receives a server-side application ID and name snapshot,
  so filtering and historical attribution do not depend on mutable SDK input.

The application page supports create, read, edit, delete, and Key rotation.
Equivalent management endpoints are available for automation:

```text
GET    /api/applications
POST   /api/applications
GET    /api/applications/{id}
PATCH  /api/applications/{id}
DELETE /api/applications/{id}
POST   /api/applications/{id}/rotate-key
```

The previous `AGENT_OBSERVABILITY_API_KEY` service environment variable remains
as a legacy global ingestion Key for migration. It does not identify an
application, so new deployments should prefer generated application Keys.

## Administrator authentication

Application management changes access credentials and must be protected in
production. Generate an administrator secret:

```bash
cd agent-observability-web
npm run --silent generate-key

# Equivalent alternative:
openssl rand -hex 32
```

Configure it only on the web service (`agent-observability-web/.env.local`):

```dotenv
AGENT_OBSERVABILITY_ADMIN_KEY=the-generated-administrator-secret
```

The management page then requires an administrator login and stores an
HTTP-only, SameSite=Strict session token derived from the configured secret.
The raw administrator Key is not placed in the session cookie. When the
variable is absent, management is open for local development. Management API
automation can send the administrator secret directly as an
`Authorization: Bearer ...` header instead of creating a browser session.

Store each generated application Key in that application's secret manager and
provide it to its Java process:

```bash
export AGENT_OBSERVABILITY_MODE=PLATFORM
export AGENT_OBSERVABILITY_ENDPOINT="http://localhost:3000/api/traces"
export AGENT_OBSERVABILITY_API_KEY="the-application-key-shown-once"
```

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);
```

Do not add either administrator or application Keys to Git or logs. Terminate
TLS at a reverse proxy or hosting platform. Trace dashboard and GET query APIs
remain readable without an administrator session in this MVP; place the entire
service behind an authenticated gateway if Trace data must not be public.

Trace deletion is permanent and uses the same administrator authentication as
application management. Browser requests must also pass a same-origin check.
Each record is addressed by both application ID and Turn ID, so deleting one
application's Turn cannot remove another application's record with the same
Turn ID. The batch API accepts at most 500 identities per request; the
dashboard automatically splits larger selections into bounded requests.

```text
DELETE /api/traces/{turnId}?applicationId={applicationId}
DELETE /api/traces
```

The batch request body is `{ "traces": [{ "turnId": "...", "applicationId":
"..." }] }`.

The dashboard treats one root Turn created by a caller invocation as one
**Task**. Descendant Agent-as-Tool Turns are attached through `parentTurnId`,
so a Supervisor and all of its SubAgents appear as one row even though the
receiver still stores their immutable Turn documents separately. Task-level
Steps, Model calls, Tool calls, errors, streaming events, and Token usage are
summed across those documents. Status and wall-clock duration come from the
root Turn because that is the outcome observed by the human caller.

The original Turn-level query and deletion APIs remain unchanged. When a Task
is deleted in the dashboard, it expands the Task into its Turn identities and
uses the existing bounded batch API. This keeps existing integrations and
stored schema versions compatible.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `AGENT_OBSERVABILITY_ADMIN_KEY` | empty | Enables administrator login for application CRUD and Key rotation |
| `AGENT_OBSERVABILITY_API_KEY` | empty | Legacy global ingestion Key without application attribution |
| `AGENT_OBSERVABILITY_SECURE_COOKIES` | automatic | Set `true` to force the administrator session cookie to HTTPS-only |
| `AGENT_OBSERVABILITY_DATA_DIR` | `.data` in the web project | Absolute or relative trace data directory |
| `AGENT_OBSERVABILITY_RETENTION` | `5000` | Maximum local trace documents retained |

Trace filenames are SHA-256 hashes of application and Turn IDs. Application
records store Key hashes, never plaintext Keys. Both stores use a temporary
file plus rename so readers do not see partially written data. Files are
created with owner-only permissions where the operating system honors POSIX
modes.

## Storage and deployment

The local `TraceStore` is deliberately optimized for an MVP:

- it works for local development and one long-running Node.js instance;
- it serializes writes inside that process and prunes the oldest files;
- it does not coordinate multiple replicas;
- ephemeral/serverless filesystems may discard its data after a restart;
- listing scans retained files and is not intended for millions of traces.

For production or horizontal scaling, implement the same `TraceStore`
interface with PostgreSQL, ClickHouse, object storage, or a telemetry backend.
The receiver accepts schema versions 1, 2, and 3. Version 3 adds raw Provider
request/response payloads and separate normalized SDK views; older local
traces remain readable.

Build and run the standalone server with:

```bash
npm run lint
npm run build
npm run start
```

Mount `AGENT_OBSERVABILITY_DATA_DIR` on a persistent volume when keeping the
local store. The standard `next start` server is suitable for the single-node
MVP; a container or process supervisor should own its lifecycle.

## Privacy and operational behavior

The `AgentObservability.platform(...)` convenience methods capture bounded
prompts, Model responses, Tool arguments, Tool results, and final answers so
the node inspector is useful out of the box. Builder-based configurations can
disable this with `.captureContent(false)`. Trace content, names, errors,
metadata, and resource attributes can all be sensitive. Protect the data
directory, use HTTPS outside localhost, rotate ingestion keys, and apply
retention appropriate to the application.

For bundled OpenAI-compatible, OpenAI Responses, and Anthropic HTTP Models,
schema version 3 shows the actual Provider JSON fields under **Provider
request** and **Provider response**. The canonical Core representation remains
available under **SDK input** and **SDK output**. Streaming responses show the
captured SSE event blocks with normalized LF line endings. Provider request
headers are excluded, and
captured endpoint URLs omit query strings and fragments.

Platform delivery never blocks the Agent Loop on network I/O. A bounded queue
drops the newest trace when full and exposes counters on
`PlatformTraceExporter`. Monitor failed and dropped counts; observability must
not silently become a reliability dependency of the Agent itself.
