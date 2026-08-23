import type { NextRequest } from "next/server";

/** JSON mutations from browsers must originate from this deployment. */
export function sameOrigin(request: NextRequest): boolean {
  const origin = request.headers.get("origin");
  return !origin || origin === request.nextUrl.origin;
}

export class ApiRequestError extends Error {
  constructor(public readonly status: 400 | 413, message: string) {
    super(message);
    this.name = "ApiRequestError";
  }
}

export async function readJsonObject(
  request: NextRequest,
  maxBytes = 16 * 1_024,
): Promise<Record<string, unknown>> {
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (declaredLength > maxBytes) {
    throw new ApiRequestError(413, `Request body exceeds ${maxBytes} bytes`);
  }
  if (!request.body) throw new ApiRequestError(400, "JSON body is required");

  const reader = request.body.getReader();
  const chunks: Buffer[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new ApiRequestError(413, `Request body exceeds ${maxBytes} bytes`);
    }
    chunks.push(Buffer.from(value));
  }

  try {
    const parsed: unknown = JSON.parse(
      Buffer.concat(chunks, total).toString("utf8"),
    );
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new ApiRequestError(400, "JSON body must be an object");
    }
    return parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof ApiRequestError) throw error;
    throw new ApiRequestError(400, "Invalid JSON body");
  }
}
