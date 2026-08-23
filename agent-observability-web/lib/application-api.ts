import { NextResponse } from "next/server";
import { ApplicationStoreError } from "./application-store";
import { ApiRequestError } from "./api-security";

export function applicationErrorResponse(error: unknown) {
  if (error instanceof ApiRequestError) {
    return NextResponse.json({ error: error.message }, { status: error.status });
  }
  if (error instanceof ApplicationStoreError) {
    const status = error.code === "NOT_FOUND"
      ? 404
      : error.code === "CONFLICT"
        ? 409
        : 400;
    return NextResponse.json({ error: error.message }, { status });
  }
  console.error("Application operation failed", error);
  return NextResponse.json(
    { error: "Application storage unavailable" },
    { status: 503 },
  );
}

export function adminRequiredResponse() {
  return NextResponse.json(
    { error: "Administrator authentication required" },
    { status: 401 },
  );
}

export function invalidOriginResponse() {
  return NextResponse.json({ error: "Invalid request origin" }, { status: 403 });
}
