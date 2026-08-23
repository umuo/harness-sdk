import { NextRequest, NextResponse } from "next/server";
import { sameOrigin } from "../../../../../lib/api-security";
import { isAdminRequest } from "../../../../../lib/admin-auth";
import {
  adminRequiredResponse,
  applicationErrorResponse,
  invalidOriginResponse,
} from "../../../../../lib/application-api";
import { applicationStore } from "../../../../../lib/application-store";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ applicationId: string }> },
) {
  if (!sameOrigin(request)) return invalidOriginResponse();
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    const { applicationId } = await context.params;
    return NextResponse.json(await applicationStore.rotateKey(applicationId));
  } catch (error) {
    return applicationErrorResponse(error);
  }
}
