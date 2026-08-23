import { NextRequest, NextResponse } from "next/server";
import { isAdminRequest } from "../../../../lib/admin-auth";
import {
  adminRequiredResponse,
  invalidOriginResponse,
} from "../../../../lib/application-api";
import { sameOrigin } from "../../../../lib/api-security";
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

export async function DELETE(
  request: NextRequest,
  context: { params: Promise<{ turnId: string }> },
) {
  if (!sameOrigin(request)) return invalidOriginResponse();
  if (!isAdminRequest(request)) return adminRequiredResponse();

  try {
    const { turnId } = await context.params;
    if (!turnId || turnId.length > 256) {
      return NextResponse.json({ error: "Invalid turnId" }, { status: 400 });
    }
    const applicationId =
      request.nextUrl.searchParams.get("applicationId") ?? "";
    if (applicationId.length > 256) {
      return NextResponse.json(
        { error: "Invalid applicationId" },
        { status: 400 },
      );
    }
    if (!(await traceStore.delete(turnId, applicationId))) {
      return NextResponse.json({ error: "Trace not found" }, { status: 404 });
    }
    return NextResponse.json({ deleted: true, turnId, applicationId });
  } catch (error) {
    console.error("Failed to delete Agent trace", error);
    return NextResponse.json(
      { error: "Trace storage unavailable" },
      { status: 503 },
    );
  }
}
