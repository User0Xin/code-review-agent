import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const script = fileURLToPath(new URL("../../../scripts/dev.mjs", import.meta.url));

// Substitute only the pnpm CLI boundary; execute the production supervisor unchanged.
async function runDev(failingService?: string) {
  const directory = mkdtempSync(join(tmpdir(), "review-dev-exit-"));
  const eventsFile = join(directory, "events.jsonl");
  writeFileSync(eventsFile, "");
  writeFileSync(
    join(directory, "pnpm"),
    `#!${process.execPath}
const { appendFileSync, readFileSync } = require("node:fs");
if (process.argv.includes("build")) process.exit(0);
const service = process.argv.includes("@review/server") ? "server" : "web";
const log = (event) => appendFileSync(process.env.REVIEW_DEV_EVENTS,
  JSON.stringify({ service, event, pid: process.pid }) + "\\n");
process.on("SIGTERM", () => { log("stopped"); process.exit(0); });
log("started");
setInterval(() => {
  const events = readFileSync(process.env.REVIEW_DEV_EVENTS, "utf8")
    .trim().split("\\n").filter(Boolean).map(JSON.parse);
  if (service === process.env.REVIEW_DEV_FAIL &&
      events.filter((event) => event.event === "started").length === 2) {
    log("failed");
    process.exit(23);
  }
}, 10);
`,
    { mode: 0o755 },
  );
  const events = (): Array<{ service: string; event: string; pid: number }> =>
    readFileSync(eventsFile, "utf8")
      .trim()
      .split("\n")
      .filter(Boolean)
      .map((line) => JSON.parse(line));
  const child = spawn(process.execPath, [script], {
    cwd: directory,
    env: {
      ...process.env,
      PATH: `${directory}${delimiter}${process.env.PATH ?? ""}`,
      REVIEW_DEV_EVENTS: eventsFile,
      REVIEW_DEV_FAIL: failingService ?? "",
    },
    stdio: "ignore",
  });
  const exited = new Promise<number | null>((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", resolve);
  });
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    const finished = Promise.race([
      exited,
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => reject(new Error("dev supervisor did not exit")), 5000);
      }),
    ]);
    if (!failingService) {
      for (let attempt = 0; attempt < 200; attempt++) {
        if (events().filter((event) => event.event === "started").length === 2) {
          break;
        }
        await delay(10);
      }
      expect(events().filter((event) => event.event === "started")).toHaveLength(2);
      child.kill("SIGINT");
    }
    return { code: await finished, events: events() };
  } finally {
    clearTimeout(timeout);
    child.kill("SIGTERM");
    // Remove any process group left behind if a regression prevents supervisor cleanup.
    for (const { pid } of events().filter((event) => event.event === "started")) {
      try {
        process.kill(-pid, "SIGKILL");
      } catch {
        // The supervisor normally already reaped this process group.
      }
    }
    rmSync(directory, { recursive: true, force: true });
  }
}

// Executable CLI fixtures and production process-group cleanup use POSIX semantics.
describe.skipIf(process.platform === "win32")("development supervisor", () => {
  it.each(["server", "web"])(
    "preserves %s failure after its sibling exits cleanly",
    async (service) => {
      const result = await runDev(service);
      expect(result.code).toBe(23);
      expect(result.events).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ service, event: "failed" }),
          expect.objectContaining({
            service: service === "server" ? "web" : "server",
            event: "stopped",
          }),
        ]),
      );
    },
  );

  it("stops both services successfully on user interrupt", async () => {
    const result = await runDev();
    expect(result.code).toBe(0);
    expect(result.events.filter((event) => event.event === "stopped")).toHaveLength(2);
  });
});
