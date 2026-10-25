import { randomBytes } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { Store } from "../src/store.js";
import { seedWorkspace } from "./fixtures/stored-workspace.js";

const cleanup: Array<() => void> = [];
afterEach(() => {
  for (const close of cleanup.splice(0).reverse()) {
    close();
  }
});
describe("SQLite durable storage", () => {
  it("refuses an incorrect master key before accepting requests or processing jobs", () => {
    const folder = mkdtempSync(join(tmpdir(), "review-key-"));
    cleanup.push(() => rmSync(folder, { recursive: true, force: true }));
    const filename = join(folder, "review.db");
    const firstKey = randomBytes(32).toString("base64");
    const store = new Store(filename, firstKey);
    store.vault.set("model:1", "secret");
    store.close();
    expect(() => new Store(filename, randomBytes(32).toString("base64"))).toThrow(
      "无法解密已保存凭据",
    );
    const restored = new Store(filename, firstKey);
    cleanup.push(() => restored.close());
    expect(restored.vault.get("model:1")).toBe("secret");
  });
  it("keeps configuration and encrypted credentials across a restart", () => {
    const folder = mkdtempSync(join(tmpdir(), "review-store-"));
    cleanup.push(() => rmSync(folder, { recursive: true, force: true }));
    const filename = join(folder, "review.db");
    let store = new Store(filename);
    store.models.save({
      id: "m1",
      name: "local model",
      baseUrl: "http://127.0.0.1:9999/v1",
      model: "test",
      enabled: true,
      hasApiKey: true,
      createdAt: "2026-10-22",
      updatedAt: "2026-10-22",
    });
    store.vault.set("model:m1", "private-test-key-never-plaintext");
    store.close();
    expect(readFileSync(filename).includes(Buffer.from("private-test-key-never-plaintext"))).toBe(
      false,
    );
    store = new Store(filename);
    cleanup.push(() => store.close());
    expect(store.models.get("m1")?.name).toBe("local model");
    expect(store.vault.get("model:m1")).toBe("private-test-key-never-plaintext");
    expect(store.vault.redact("upstream leaked private-test-key-never-plaintext")).toBe(
      "upstream leaked [REDACTED]",
    );
  });
  it("deduplicates jobs and reclaims interrupted work without losing payload", () => {
    const s = new Store(":memory:");
    seedWorkspace(s);
    cleanup.push(() => s.close());
    const one = s.jobs.enqueue("review", { sessionId: "s1" }, "review:s1:sha");
    const two = s.jobs.enqueue("review", { sessionId: "s2" }, "review:s1:sha");
    expect(two.id).toBe(one.id);
    expect(s.jobs.claim()?.attempts).toBe(1);
    expect(s.jobs.claim()).toBeUndefined();
    s.jobs.recover();
    const recovered = s.jobs.claim();
    expect(recovered?.id).toBe(one.id);
    expect(recovered?.payload).toEqual({ sessionId: "s1" });
    s.jobs.complete(one.id);
    expect(s.jobs.claim()).toBeUndefined();
  });
  it("rolls back receipt and task atomically and only accepts delivery once", () => {
    const s = new Store(":memory:");
    seedWorkspace(s);
    cleanup.push(() => s.close());
    expect(() =>
      s.transaction(() => {
        s.events.record("delivery", "p", "github", {
          id: "delivery",
          type: "pr",
          repository: "team/repo",
          number: 1,
        });
        s.jobs.enqueue(
          "event",
          {
            projectId: "p",
            event: { id: "delivery", type: "pr", repository: "team/repo", number: 1 },
          },
          "delivery",
        );
        throw new Error("crash");
      }),
    ).toThrow("crash");
    expect(s.events.has("delivery")).toBe(false);
    expect(s.jobs.list()).toHaveLength(0);
    s.transaction(() => {
      s.events.record("delivery", "p", "github", {
        id: "delivery",
        type: "pr",
        repository: "team/repo",
        number: 1,
      });
      s.jobs.enqueue(
        "event",
        {
          projectId: "p",
          event: { id: "delivery", type: "pr", repository: "team/repo", number: 1 },
        },
        "delivery",
      );
    });
    expect(s.events.has("delivery")).toBe(true);
    expect(s.jobs.list()).toHaveLength(1);
  });
  it("limits retries and persists a safely redacted failure", () => {
    const s = new Store(":memory:");
    seedWorkspace(s);
    cleanup.push(() => s.close());
    s.vault.set("repo:p", "sensitive-token");
    const job = s.jobs.enqueue("summary", { projectId: "p" }, "summary:p");
    for (let n = 0; n < 3; n++) {
      s.jobs.claim();
      s.jobs.recover();
    }
    const failed = s.jobs.list()[0]!;
    expect(failed.status).toBe("failed");
    expect(s.jobs.retry(job.id).status).toBe("queued");
    expect(s.jobs.claim()?.attempts).toBe(1);
    s.jobs.fail(job.id, "bad sensitive-token");
    expect(s.jobs.list()[0]?.error).toBe("bad [REDACTED]");
  });
});
