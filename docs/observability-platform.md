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
- `GET /api/health` readiness information;
- an overview with Turn count, success rate, P95 duration, Tokens, Tool errors,
  filtering, and manual/automatic refresh;
- Chinese and English interfaces with Chinese selected by default and the
  browser choice persisted locally;
- a Turn detail page with error context, usage, correlation fields,
  attributes, and a Turn/Step/Model/Tool span waterfall;
- atomic local-file persistence behind a small `TraceStore` interface.

The web project is intentionally outside the Maven reactor. Java 8 remains the
SDK runtime requirement; the dashboard uses Node.js 20.9 or newer.

## Run locally

```bash
cd agent-observability-web
npm ci
npm run dev
```

Open `http://localhost:3000`. Then register platform observability on the Java
Agent:

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces"
);

Agent agent = Agent.builder()
    .name("assistant")
    .model(model)
    .plugin(observability)
    .build();
```

Close `observability` when the application shuts down so its asynchronous
queue can drain.

## Authentication

`AGENT_OBSERVABILITY_API_KEY` is not issued by an LLM provider. It is a shared
Bearer secret generated and owned by the observability platform operator. A
convenience command creates 32 cryptographically secure random bytes and prints
them as a 64-character hexadecimal value:

```bash
cd agent-observability-web
npm run --silent generate-key
```

OpenSSL can generate an equivalent key:

```bash
openssl rand -hex 32
```

Generate it once, store it in a secret manager or local `.env.local` file, and
configure the exact same value on the web service and Java process. Do not add
the value to Git, application logs, or command examples committed to the
repository.

Web service configuration (`agent-observability-web/.env.local`):

```dotenv
AGENT_OBSERVABILITY_API_KEY=the-generated-64-character-value
```

The included `ObservabilityExample` reads these Java process variables. Normal
applications may use their own configuration system but must select platform
mode explicitly:

```bash
export AGENT_OBSERVABILITY_MODE=PLATFORM
export AGENT_OBSERVABILITY_ENDPOINT="http://localhost:3000/api/traces"
export AGENT_OBSERVABILITY_API_KEY="the-generated-64-character-value"
```

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);
```

When the environment variable is absent, ingestion is unauthenticated for
local development. In production, configure the secret and terminate TLS at a
reverse proxy or hosting platform. The dashboard and GET APIs have no user
authentication in this MVP; place the service behind an authenticated gateway
if traces must not be publicly readable.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `AGENT_OBSERVABILITY_API_KEY` | empty | Required Bearer token for ingestion when set |
| `AGENT_OBSERVABILITY_DATA_DIR` | `.data` in the web project | Absolute or relative trace data directory |
| `AGENT_OBSERVABILITY_RETENTION` | `5000` | Maximum local trace documents retained |

Trace filenames are SHA-256 hashes of Turn IDs, and writes use a temporary file
plus rename. This avoids using remote identifiers as filesystem paths and
prevents readers from seeing a partially written document. Files are created
with owner-only permissions where the operating system honors POSIX modes.

## Storage and deployment

The local `TraceStore` is deliberately optimized for an MVP:

- it works for local development and one long-running Node.js instance;
- it serializes writes inside that process and prunes the oldest files;
- it does not coordinate multiple replicas;
- ephemeral/serverless filesystems may discard its data after a restart;
- listing scans retained files and is not intended for millions of traces.

For production or horizontal scaling, implement the same `TraceStore`
interface with PostgreSQL, ClickHouse, object storage, or a telemetry backend.
Keep `schemaVersion` and the HTTP ingestion contract stable so Java Agents do
not need to change.

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

The SDK does not capture prompts, model answers, Tool arguments, or Tool
results unless `.captureContent(true)` is explicitly enabled. Trace names,
errors, metadata, and resource attributes can still be sensitive. Protect the
data directory, use HTTPS outside localhost, rotate ingestion keys, and apply
retention appropriate to the application.

Platform delivery never blocks the Agent Loop on network I/O. A bounded queue
drops the newest trace when full and exposes counters on
`PlatformTraceExporter`. Monitor failed and dropped counts; observability must
not silently become a reliability dependency of the Agent itself.
