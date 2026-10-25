import express from "express";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { createApp } from "./app.js";
import { readEnvironment } from "./configuration/environment.js";
import { acquireProcessLock } from "./runtime/process-lock.js";

async function bootstrap(): Promise<void> {
  const environment = readEnvironment();
  const releaseLock = acquireProcessLock(environment.databasePath);
  let runtime: Awaited<ReturnType<typeof createApp>> | undefined;
  try {
    runtime = await createApp();
    if (existsSync(environment.frontendPath)) {
      runtime.app.use(express.static(environment.frontendPath));
      runtime.app.use((request: express.Request, response: express.Response, next: () => void) => {
        if (request.method === "GET" && !request.path.startsWith("/api/")) {
          response.sendFile(resolve(environment.frontendPath, "index.html"));
        } else {
          next();
        }
      });
    }
    await runtime.app.listen(environment.port, environment.host);
    console.log(`SparkShield: http://${environment.host}:${environment.port}`);
    const activeRuntime = runtime;
    const shutdown = () => {
      void activeRuntime
        .close()
        .then(() => {
          releaseLock();
          process.exit(0);
        })
        .catch(() => {
          console.error("服务未能正常关闭，进程锁将由下一次启动检查");
          process.exit(1);
        });
    };
    process.once("SIGINT", shutdown);
    process.once("SIGTERM", shutdown);
  } catch (error) {
    try {
      await runtime?.close();
    } finally {
      releaseLock();
    }
    throw error;
  }
}

await bootstrap();
