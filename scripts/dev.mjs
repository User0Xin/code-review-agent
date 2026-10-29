import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { existsSync } from "node:fs";
if (existsSync(".env")) process.loadEnvFile(".env");
const build = spawn("pnpm", ["--filter", "@review/contracts", "build"], {
  stdio: "inherit",
});
build.on("exit", (code) => {
  if (code) {
    process.exit(code);
    return;
  }
  const common = {
    ...process.env,
    REVIEW_DATABASE_PATH: resolve(process.env.REVIEW_DATABASE_PATH || "data/review.db"),
  };
  const options = {
    stdio: "inherit",
    env: common,
    detached: process.platform !== "win32",
  };
  const children = [
    spawn("pnpm", ["--filter", "@review/server", "dev"], options),
    spawn("pnpm", ["--filter", "@review/web", "dev"], options),
  ];
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    for (const c of children) {
      try {
        if (process.platform === "win32") c.kill("SIGTERM");
        else process.kill(-c.pid, "SIGTERM");
      } catch {}
    }
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
  for (const c of children)
    c.on("exit", (code) => {
      stop();
      // Cleanup can exit successfully after the other service has already failed.
      process.exitCode = process.exitCode || code || 0;
    });
});
