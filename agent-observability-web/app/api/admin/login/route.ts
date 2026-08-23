import { NextRequest, NextResponse } from "next/server";
import {
  ApiRequestError,
  readJsonObject,
  sameOrigin,
} from "../../../../lib/api-security";
import {
  ADMIN_SESSION_COOKIE,
  adminAuthenticationConfigured,
  adminSessionToken,
  secureAdminCookie,
  validateAdminKey,
} from "../../../../lib/admin-auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  if (!sameOrigin(request)) {
    return NextResponse.json({ error: "Invalid request origin" }, { status: 403 });
  }
  if (!adminAuthenticationConfigured()) {
    return NextResponse.json({ authenticated: true });
  }
  try {
    const body = await readJsonObject(request, 4_096);
    if (
      typeof body.adminKey !== "string" ||
      body.adminKey.length > 512 ||
      !validateAdminKey(body.adminKey)
    ) {
      return NextResponse.json({ error: "Invalid administrator key" }, { status: 401 });
    }
    const response = NextResponse.json({ authenticated: true });
    response.cookies.set(ADMIN_SESSION_COOKIE, adminSessionToken(), {
      httpOnly: true,
      sameSite: "strict",
      secure: secureAdminCookie(request),
      path: "/",
      maxAge: 8 * 60 * 60,
    });
    return response;
  } catch (error) {
    if (error instanceof ApiRequestError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    return NextResponse.json({ error: "Authentication unavailable" }, { status: 503 });
  }
}
