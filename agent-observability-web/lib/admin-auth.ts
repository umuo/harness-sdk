import { createHmac, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import type { NextRequest } from "next/server";

export const ADMIN_SESSION_COOKIE = "agent-observatory-admin-session";
const SESSION_PURPOSE = "agent-observatory-admin-session-v1";

export function adminAuthenticationConfigured(): boolean {
  return Boolean(process.env.AGENT_OBSERVABILITY_ADMIN_KEY);
}

export function validateAdminKey(candidate: string): boolean {
  const expected = process.env.AGENT_OBSERVABILITY_ADMIN_KEY;
  return !expected || safeEqual(candidate, expected);
}

export function adminSessionToken(): string {
  const key = process.env.AGENT_OBSERVABILITY_ADMIN_KEY;
  if (!key) return "local-development";
  return createHmac("sha256", key).update(SESSION_PURPOSE).digest("hex");
}

export function secureAdminCookie(request: NextRequest): boolean {
  const forwardedProtocol = request.headers
    .get("x-forwarded-proto")
    ?.split(",")[0]
    .trim();
  return (
    process.env.AGENT_OBSERVABILITY_SECURE_COOKIES === "true" ||
    request.nextUrl.protocol === "https:" ||
    forwardedProtocol === "https"
  );
}

export function isAdminRequest(request: NextRequest): boolean {
  if (!adminAuthenticationConfigured()) return true;
  const authorization = request.headers.get("authorization") ?? "";
  const bearer = /^Bearer\s+(.+)$/i.exec(authorization)?.[1]?.trim();
  if (bearer && validateAdminKey(bearer)) return true;
  return safeEqual(
    request.cookies.get(ADMIN_SESSION_COOKIE)?.value ?? "",
    adminSessionToken(),
  );
}

export async function isCurrentAdmin(): Promise<boolean> {
  if (!adminAuthenticationConfigured()) return true;
  return safeEqual(
    (await cookies()).get(ADMIN_SESSION_COOKIE)?.value ?? "",
    adminSessionToken(),
  );
}

function safeEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left, "utf8");
  const rightBytes = Buffer.from(right, "utf8");
  return (
    leftBytes.length === rightBytes.length &&
    timingSafeEqual(leftBytes, rightBytes)
  );
}
