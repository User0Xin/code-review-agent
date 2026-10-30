import { afterEach, describe, expect, it, vi } from "vitest";
import { IntegrationError } from "../src/integrations/integration-error.js";
import { Worker } from "../src/jobs/worker.js";
import type { AgentPort, RepositoryPort } from "../src/ports.js";
import { Store } from "../src/store.js";
import { Workflow } from "../src/workflow/workflow.js";
import {
  fixtureModel,
  fixtureProject,
  fixtureSnapshot,
  fixtureTime,
  seedWorkspace,
} from "./fixtures/stored-workspace.js";

const stores: Store[] = [];
afterEach(() => {
  for (const store of stores.splice(0)) {
    store.close();
  }
  vi.useRealTimers();
});

function publicationFailure(error: unknown) {
  const store = new Store(":memory:");
  stores.push(store);
  seedWorkspace(store);
  store.vault.set("repo:p", "worker-fixture-token");
  const repository: RepositoryPort = {
    getPullRequest: vi.fn(async () => fixtureSnapshot),
    getRevision: vi.fn(async () => fixtureSnapshot),
    listPullRequests: vi.fn(async () => []),
    getComments: vi.fn(async () => []),
    publishComment: vi.fn(async () => {
      throw error;
    }),
  };
  const agent: AgentPort = {
    review: vi.fn(async () => ({ summary: "", findings: [], resolvedFindingIds: [] })),
    reply: vi.fn(async () => ({ body: "", verdict: "uncertain" as const })),
    chat: vi.fn(async () => ""),
    summarize: vi.fn(async () => []),
    test: vi.fn(async () => ""),
  };
  const workflow = new Workflow(store, repository, agent);
  const worker = new Worker(store, workflow);
  const run = store.runs.save({
    id: "run:worker",
    projectId: "p",
    sessionId: "s1",
    headSha: fixtureSnapshot.headSha,
    status: "completed",
    trigger: "manual",
    standardVersion: 1,
    standardSnapshot: fixtureProject.standard,
    modelSnapshot: fixtureModel,
    experienceIds: [],
    summary: "Review complete",
    snapshot: fixtureSnapshot,
    projectSnapshot: fixtureProject,
    createdAt: fixtureTime,
    completedAt: fixtureTime,
  });
  const job = workflow.queuePublish(run.id);
  return { store, worker, repository, job };
}

describe("durable worker failure handling", () => {
  it("stops a publication after the first HTTP 401 instead of retrying without new credentials", async () => {
    vi.useFakeTimers();
    const f = publicationFailure(new IntegrationError("代码平台请求失败（HTTP 401）", false, 401));

    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 1,
      lockedAt: null,
      error: "代码平台请求失败（HTTP 401）",
    });
    await vi.advanceTimersByTimeAsync(60_000);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)?.attempts).toBe(1);
    expect(f.repository.publishComment).toHaveBeenCalledTimes(1);
  });

  it.each([403, 422])("does not retry a non-retryable HTTP %i", async (status) => {
    vi.useFakeTimers();
    const f = publicationFailure(new IntegrationError("Publication rejected", false, status));

    await f.worker.drain();
    await vi.advanceTimersByTimeAsync(60_000);
    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "failed", attempts: 1 });
    expect(f.repository.publishComment).toHaveBeenCalledTimes(1);
  });

  it("stops a business rejection when publication credentials are removed after enqueueing", async () => {
    vi.useFakeTimers();
    const f = publicationFailure(new Error("This request must not be sent"));
    f.store.vault.set("repo:p", "");

    await f.worker.drain();
    await vi.advanceTimersByTimeAsync(60_000);
    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 1,
      lockedAt: null,
      error: "发布需要仓库访问令牌，请在仓库配置中填写具有评论权限的令牌",
    });
    expect(f.repository.getRevision).not.toHaveBeenCalled();
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });

  it("redacts a terminal failure and allows an explicit retry once the failure is fixed", async () => {
    const f = publicationFailure(new IntegrationError("Rejected worker-fixture-token", false, 401));
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)?.error).toBe("Rejected [REDACTED]");
    vi.mocked(f.repository.publishComment).mockResolvedValue({
      id: "issue:published",
      body: "Published",
      author: "agent",
      isBot: true,
      createdAt: fixtureTime,
    });

    f.store.jobs.retry(f.job.id);
    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "completed",
      attempts: 1,
      error: null,
      lockedAt: null,
    });
    expect(f.repository.publishComment).toHaveBeenCalledTimes(2);
  });

  it.each([
    { name: "HTTP 408", error: new IntegrationError("Temporary worker-fixture-token", true, 408) },
    { name: "HTTP 429", error: new IntegrationError("Temporary worker-fixture-token", true, 429) },
    { name: "HTTP 500", error: new IntegrationError("Temporary worker-fixture-token", true, 500) },
    { name: "HTTP 503", error: new IntegrationError("Temporary worker-fixture-token", true, 503) },
    { name: "network", error: new IntegrationError("Temporary worker-fixture-token", true) },
    { name: "timeout", error: new IntegrationError("Temporary worker-fixture-token", true) },
    { name: "unknown Error", error: new Error("Temporary worker-fixture-token") },
    {
      name: "unknown Error with an unrelated retryable property",
      error: Object.assign(new Error("Temporary worker-fixture-token"), { retryable: false }),
    },
    { name: "unknown thrown value", error: "Temporary worker-fixture-token" },
  ])("preserves bounded retries and redaction for $name failures", async ({ error }) => {
    vi.useFakeTimers();
    const f = publicationFailure(error);
    const startedAt = Date.now();

    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "queued",
      attempts: 1,
      availableAt: startedAt + 2000,
      lockedAt: null,
      error: "Temporary [REDACTED]",
    });
    await vi.advanceTimersByTimeAsync(1999);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)?.attempts).toBe(1);

    await vi.advanceTimersByTimeAsync(1);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "queued",
      attempts: 2,
      availableAt: startedAt + 6000,
      error: "Temporary [REDACTED]",
    });
    await vi.advanceTimersByTimeAsync(3999);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)?.attempts).toBe(2);

    await vi.advanceTimersByTimeAsync(1);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 3,
      lockedAt: null,
      error: "Temporary [REDACTED]",
    });
    await vi.advanceTimersByTimeAsync(60_000);
    await f.worker.drain();
    expect(f.store.jobs.get(f.job.id)?.attempts).toBe(3);
    expect(f.repository.publishComment).toHaveBeenCalledTimes(3);
  });
});
