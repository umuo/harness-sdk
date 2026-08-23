import { NextRequest, NextResponse } from "next/server";
import { readJsonObject, sameOrigin } from "../../../lib/api-security";
import { isAdminRequest } from "../../../lib/admin-auth";
import {
  adminRequiredResponse,
  applicationErrorResponse,
  invalidOriginResponse,
} from "../../../lib/application-api";
import { applicationStore } from "../../../lib/application-store";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    return NextResponse.json({ applications: await applicationStore.list() });
  } catch (error) {
    return applicationErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  if (!sameOrigin(request)) return invalidOriginResponse();
  if (!isAdminRequest(request)) return adminRequiredResponse();
  try {
    const body = await readJsonObject(request);
    const created = await applicationStore.create({
      name: body.name as string,
      description: body.description as string | undefined,
    });
    return NextResponse.json(created, { status: 201 });
  } catch (error) {
    return applicationErrorResponse(error);
  }
}
