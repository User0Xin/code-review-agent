import { resolve } from "node:path";
import { z } from "zod";

const environmentSchema = z.object({
  HOST: z.string().min(1).default("127.0.0.1"),
  PORT: z.coerce.number().int().min(1).max(65535).default(3100),
  REVIEW_DATABASE_PATH: z.string().min(1).default("./data/review.db"),
  REVIEW_WEB_DIST: z.string().min(1).default("../web/dist"),
  REVIEW_ALLOWED_ORIGINS: z.string().default(""),
});

export function readEnvironment(environment: NodeJS.ProcessEnv = process.env) {
  const parsed = environmentSchema.parse(environment);
  return {
    host: parsed.HOST,
    port: parsed.PORT,
    databasePath: resolve(parsed.REVIEW_DATABASE_PATH),
    frontendPath: resolve(parsed.REVIEW_WEB_DIST),
    allowedOrigins: parsed.REVIEW_ALLOWED_ORIGINS.split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
  };
}
