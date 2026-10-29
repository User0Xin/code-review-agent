import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Worker } from "../src/jobs/worker.js";
import type { AgentPort, RepositoryPort } from "../src/ports.js";
import { Store } from "../src/store.js";
import { Workflow } from "../src/workflow/workflow.js";
import { fixtureSnapshot, seedWorkspace } from "./fixtures/stored-workspace.js";

const stores: Store[] = [];
const directories: string[] = [];
afterEach(() => {
  for (const store of stores.splice(0)) {
    store.close();
  }
  for (const directory of directories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
  vi.useRealTimers();
});

function fixture(store: Store) {
  stores.push(store);
  const repository: RepositoryPort = {
    getPullRequest: async () => structuredClone(fixtureSnapshot),
    getRevision: async () => structuredClone(fixtureSnapshot),
    listPullRequests: async () => [],
    getComments: async () => [],
    publishComment: async () => {
      throw new Error("Unexpected external publication");
    },
  };
  const agent: AgentPort = {
    review: vi.fn(async () => ({ summary: "ok", findings: [], resolvedFindingIds: [] })),
    reply: async () => ({ body: "reply", verdict: "uncertain" }),
    chat: async () => "chat",
    summarize: async () => [],
    test: async () => "ok",
  };
  const workflow = new Workflow(store, repository, agent);
  return { store, agent, workflow, worker: new Worker(store, workflow) };
}

async function interruptedReview(attempt: number) {
  vi.useFakeTimers();
  const directory = mkdtempSync(join(tmpdir(), "review-recovery-"));
  directories.push(directory);
  const path = join(directory, "review.db");
  const f = fixture(new Store(path));
  seedWorkspace(f.store);
  let entered!: () => void;
  const reviewing = new Promise<void>((resolve) => {
    entered = resolve;
  });
  for (let n = 1; n < attempt; n++) {
    vi.mocked(f.agent.review).mockRejectedValueOnce(new Error("temporary failure"));
  }
  vi.mocked(f.agent.review).mockImplementationOnce(() => {
    entered();
    return new Promise(() => {});
  });
  const job = f.workflow.queueReview("s1");
  for (let n = 1; n < attempt; n++) {
    await f.worker.drain();
    vi.setSystemTime(Date.now() + 60_000);
  }
  const claimed = f.store.jobs.claim();
  if (!claimed) {
    throw new Error("Expected an eligible review job");
  }
  void f.workflow.processJob(claimed);
  await reviewing;
  const run = f.store.runs.get(`run:${job.id}`);
  if (!run) {
    throw new Error("Expected a persisted review run");
  }
  expect(run.status).toBe("running");
  expect(f.store.jobs.get(job.id)).toMatchObject({ status: "running", attempts: attempt });
  // The model boundary stays suspended while the actual SQLite connection is reopened.
  f.store.close();
  stores.splice(stores.indexOf(f.store), 1);
  return { ...fixture(new Store(path)), job, run };
}

describe("interrupted review recovery", () => {
  it("ends an exhausted review and job together without granting more attempts", async () => {
    const f = await interruptedReview(3);
    f.store.jobs.recover();
    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 3,
      lockedAt: null,
    });
    const run = f.store.runs.get(f.run.id);
    expect(run).toMatchObject({
      status: "failed",
      executionStatus: "failed",
      snapshot: f.run.snapshot,
    });
    expect(run?.error).toContain("重启");
    expect(run?.completedAt).toBeDefined();
    expect(f.store.jobs.claim()).toBeUndefined();
    f.store.jobs.recover();
    expect(f.store.runs.get(f.run.id)).toEqual(run);
    expect(f.store.jobs.get(f.job.id)?.attempts).toBe(3);
  });

  it.each([1, 2])(
    "resumes interrupted attempt %i within the original retry budget",
    async (attempt) => {
      const f = await interruptedReview(attempt);
      f.store.jobs.recover();
      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "queued", attempts: attempt });
      expect(f.store.runs.get(f.run.id)).toEqual(f.run);
      await f.worker.drain();
      expect(f.store.jobs.get(f.job.id)).toMatchObject({
        status: "completed",
        attempts: attempt + 1,
      });
      expect(f.store.runs.get(f.run.id)?.status).toBe("completed");
    },
  );

  it.each(["completed", "superseded"] as const)("preserves a persisted %s run", async (status) => {
    const f = await interruptedReview(3);
    f.store.runs.save({
      ...f.run,
      status,
      summary: "Persisted result",
      completedAt: new Date().toISOString(),
      ...(status === "completed"
        ? { resultSnapshot: { summary: "Persisted result", findings: [], resolvedFindingIds: [] } }
        : {}),
    });
    const before = f.store.runs.get(f.run.id);
    f.store.jobs.recover();
    expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "failed", attempts: 3 });
    expect(f.store.runs.get(f.run.id)).toEqual(before);
  });
});
