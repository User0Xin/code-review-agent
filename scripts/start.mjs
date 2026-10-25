import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
if (existsSync(".env")) process.loadEnvFile(".env");
const child = spawn(process.execPath, ["apps/server/dist/main.js"], {
  stdio: "inherit",
  env: {
    ...process.env,
    REVIEW_DATABASE_PATH: resolve(process.env.REVIEW_DATABASE_PATH || "data/review.db"),
    REVIEW_WEB_DIST: resolve("apps/web/dist"),
  },
});
process.once("SIGINT", () => child.kill("SIGINT"));
process.once("SIGTERM", () => child.kill("SIGTERM"));
child.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
