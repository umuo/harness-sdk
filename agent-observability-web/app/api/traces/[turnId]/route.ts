import { NextRequest, NextResponse } from "next/server";
import { traceStore } from "../../../../lib/trace-store";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ turnId: string }> },
) {
  const { turnId } = await context.params;
  const trace = await traceStore.get(
    turnId,
    request.nextUrl.searchParams.get("applicationId") ?? "",
  );
  if (!trace) {
    return NextResponse.json({ error: "Trace not found" }, { status: 404 });
  }
  return NextResponse.json({ trace });
}
