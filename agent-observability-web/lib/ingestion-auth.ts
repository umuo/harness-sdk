import { timingSafeEqual } from "node:crypto";
import type { NextRequest } from "next/server";
import { applicationStore } from "./application-store";

export interface IngestionIdentity {
  authorized: boolean;
  applicationId: string;
  applicationName: string;
}

export async function ingestionIdentity(
  request: NextRequest,
): Promise<IngestionIdentity> {
  const legacyKey = process.env.AGENT_OBSERVABILITY_API_KEY ?? "";
  const authorization = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(authorization);

  if (!match) {
    const applications = await applicationStore.list();
    return {
      authorized: applications.length === 0 && legacyKey.length === 0,
      applicationId: "",
      applicationName: "",
    };
  }

  const suppliedKey = match[1].trim();
  const application = await applicationStore.authenticate(suppliedKey);
  if (application) {
    return {
      authorized: true,
      applicationId: application.id,
      applicationName: application.name,
    };
  }
  if (legacyKey && safeEqual(suppliedKey, legacyKey)) {
    return { authorized: true, applicationId: "", applicationName: "" };
  }
  return { authorized: false, applicationId: "", applicationName: "" };
}

function safeEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left, "utf8");
  const rightBytes = Buffer.from(right, "utf8");
  return (
    leftBytes.length === rightBytes.length &&
    timingSafeEqual(leftBytes, rightBytes)
  );
}
