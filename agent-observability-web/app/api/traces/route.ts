import { NextRequest, NextResponse } from "next/server";
import { ingestionIdentity } from "../../../lib/ingestion-auth";
import { traceStore } from "../../../lib/trace-store";
import {
  TraceValidationError,
  validateTrace,
} from "../../../lib/trace-validation";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_BODY_BYTES = 2 * 1_024 * 1_024;

export async function POST(request: NextRequest) {
  const identity = await ingestionIdentity(request);
  if (!identity.authorized) {
    return NextResponse.json(
      { error: "Unauthorized" },
      { status: 401, headers: { "WWW-Authenticate": "Bearer" } },
    );
  }

  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_BODY_BYTES) {
    return NextResponse.json(
      { error: `Trace payload exceeds ${MAX_BODY_BYTES} bytes` },
      { status: 413 },
    );
  }

  try {
    const body = await readBoundedBody(request);
    const trace = {
      ...validateTrace(JSON.parse(body)),
      applicationId: identity.applicationId,
      applicationName: identity.applicationName,
    };
    await traceStore.save(trace);
    return NextResponse.json(
      {
        accepted: true,
        traceId: trace.traceId,
        turnId: trace.turnId,
        applicationId: trace.applicationId,
        applicationName: trace.applicationName,
      },
      { status: 202 },
    );
  } catch (error) {
    if (error instanceof PayloadTooLargeError) {
      return NextResponse.json(
        { error: error.message },
        { status: 413 },
      );
    }
    if (error instanceof SyntaxError || error instanceof TraceValidationError) {
      return NextResponse.json(
        { error: error.message },
        { status: 400 },
      );
    }
    console.error("Failed to persist Agent trace", error);
    return NextResponse.json(
      { error: "Trace storage unavailable" },
      { status: 503 },
    );
  }
}

export async function GET(request: NextRequest) {
  const limit = Number.parseInt(
    request.nextUrl.searchParams.get("limit") ?? "100",
    10,
  );
  const traces = await traceStore.list({
    limit: Number.isFinite(limit) ? limit : 100,
    status: request.nextUrl.searchParams.get("status") ?? undefined,
    agentName: request.nextUrl.searchParams.get("agent") ?? undefined,
    applicationId:
      request.nextUrl.searchParams.get("applicationId") ?? undefined,
  });
  return NextResponse.json({ traces });
}

async function readBoundedBody(request: NextRequest): Promise<string> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Buffer[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new PayloadTooLargeError();
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, total).toString("utf8");
}

class PayloadTooLargeError extends Error {
  constructor() {
    super(`Trace payload exceeds ${MAX_BODY_BYTES} bytes`);
    this.name = "PayloadTooLargeError";
  }
}
