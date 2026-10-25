import type { RequestHandler } from "express";

export function originMiddleware(
  port: number,
  additionalOrigins: readonly string[],
): RequestHandler {
  const allowed = new Set([
    `http://localhost:${port}`,
    `http://127.0.0.1:${port}`,
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    ...additionalOrigins,
  ]);
  return (request, response, next) => {
    if (
      request.headers.origin &&
      !request.path.startsWith("/api/webhooks/") &&
      !allowed.has(request.headers.origin)
    ) {
      response.status(403).json({ message: "来源不受信任" });
      return;
    }
    next();
  };
}
