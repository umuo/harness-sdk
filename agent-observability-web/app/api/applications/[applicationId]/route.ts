import { NextRequest, NextResponse } from "next/server";
import { readJsonObject, sameOrigin } from "../../../../lib/api-security";
import { isAdminRequest } from "../../../../lib/admin-auth";
import {
  adminRequiredResponse,
  applicationErrorResponse,
  invalidOriginResponse,
} from "../../../../lib/application-api";
import { applicationStore } from "../../../../lib/application-store";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type Context = { params: Promise<{ applicationId: string }> };

export async function GET(request: NextRequest, context: Context) {
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    const { applicationId } = await context.params;
    const application = await applicationStore.get(applicationId);
    if (!application) {
      return NextResponse.json({ error: "Application not found" }, { status: 404 });
    }
    return NextResponse.json({ application });
  } catch (error) {
    return applicationErrorResponse(error);
  }
}

export async function PATCH(request: NextRequest, context: Context) {
  if (!sameOrigin(request)) return invalidOriginResponse();
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    const { applicationId } = await context.params;
    const body = await readJsonObject(request);
    const application = await applicationStore.update(applicationId, {
      name: body.name as string | undefined,
      description: body.description as string | undefined,
    });
    return NextResponse.json({ application });
  } catch (error) {
    return applicationErrorResponse(error);
  }
}

export async function DELETE(request: NextRequest, context: Context) {
  if (!sameOrigin(request)) return invalidOriginResponse();
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    const { applicationId } = await context.params;
    if (!(await applicationStore.delete(applicationId))) {
      return NextResponse.json({ error: "Application not found" }, { status: 404 });
    }
    return NextResponse.json({ deleted: true, applicationId });
  } catch (error) {
    return applicationErrorResponse(error);
  }
}
