import { NextRequest, NextResponse } from "next/server";
import { sameOrigin } from "../../../../lib/api-security";
import {
  ADMIN_SESSION_COOKIE,
  secureAdminCookie,
} from "../../../../lib/admin-auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export function POST(request: NextRequest) {
  if (!sameOrigin(request)) {
    return NextResponse.json({ error: "Invalid request origin" }, { status: 403 });
  }
  const response = NextResponse.json({ authenticated: false });
  response.cookies.set(ADMIN_SESSION_COOKIE, "", {
    httpOnly: true,
    sameSite: "strict",
    secure: secureAdminCookie(request),
    path: "/",
    maxAge: 0,
  });
  return response;
}
