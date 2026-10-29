import type {
  Experience,
  FindingDraft,
  ModelConnection,
  Project,
  PullRequestSnapshot,
  RemoteComment,
} from "@review/contracts";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { diffLines } from "../src/diff.js";
import { Worker } from "../src/jobs/worker.js";
import type { AgentPort, RepositoryPort } from "../src/ports.js";
import { Store } from "../src/store.js";
import { Workflow } from "../src/workflow/workflow.js";

const now = "2026-10-22T00:00:00.000Z";
const model: ModelConnection = {
  id: "model1",
  name: "Model",
  baseUrl: "https://models.test/v1",
  model: "test-model",
  enabled: true,
  hasApiKey: true,
  createdAt: now,
  updatedAt: now,
};
const project: Project = {
  id: "project1",
  name: "Project",
  provider: "github",
  host: "https://github.com",
  repository: "owner/repo",
  modelId: model.id,
  standard: "Validate external input before processing.",
  standardVersion: 1,
  ignorePatterns: ["**/*.lock"],
  autoReview: true,
  autoPublish: false,
  hasToken: true,
  hasWebhookSecret: true,
  createdAt: now,
  updatedAt: now,
};
const initialSnapshot: PullRequestSnapshot = {
  number: 1,
  title: "Change validation",
  description: "Validate input",
  url: "https://github.com/owner/repo/pull/1",
  headSha: "head1",
  baseSha: "base1",
  branch: "feature",
  baseBranch: "main",
  state: "open",
  files: [
    {
      path: "src/index.ts",
      patch: "@@ -1,2 +1,3 @@\n const ready = true;\n+const unsafe = input;\n return ready;",
      content: "const ready = true;\nconst unsafe = input;\nreturn ready;",
      status: "modified",
      additions: 1,
      deletions: 0,
    },
  ],
  truncated: false,
};
const draft: FindingDraft = {
  title: "Unchecked input",
  body: "Check the external input.",
  path: "src/index.ts",
  line: 2,
  severity: "high",
  category: "security",
  evidence: "const unsafe = input;",
};
const summaryBody = `### 适用范围

External input validation and its callers.

### 支持证据

- The reviewer withdrew an input validation finding after the caller was checked.

### 建议

- Check whether input validation occurs in callers.

### 局限

This evidence comes from one review and does not establish a global rule.`;
const stores: Store[] = [];
const directories: string[] = [];
afterEach(() => {
  for (const store of stores.splice(0)) {
    store.close();
  }
  for (const directory of directories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function fixture(store = new Store(":memory:")) {
  stores.push(store);
  store.models.save(model);
  store.projects.save(project);
  store.vault.set(`repo:${project.id}`, "repo-secret");
  store.vault.set(`model:${model.id}`, "model-secret");
  let snapshot = structuredClone(initialSnapshot);
  const comments: RemoteComment[] = [];
  const repository: RepositoryPort = {
    getPullRequest: vi.fn(async () => structuredClone(snapshot)),
    getRevision: vi.fn(async () => ({
      headSha: snapshot.headSha,
      baseSha: snapshot.baseSha,
      startSha: snapshot.startSha,
      state: snapshot.state,
    })),
    listPullRequests: vi.fn(async () => []),
    getComments: vi.fn(async () => structuredClone(comments)),
    publishComment: vi.fn(async (_project, _token, _snapshot, comment) => {
      const remote: RemoteComment = {
        id: `review:${comments.length + 1}`,
        threadId: comment.threadId ?? `review:${comments.length + 1}`,
        replyToId: comment.replyToId,
        body: comment.body,
        author: "agent",
        isBot: true,
        createdAt: now,
      };
      comments.push(remote);
      return remote;
    }),
  };
  const agent: AgentPort = {
    review: vi.fn(async () => ({
      summary: "Review complete",
      findings: [draft],
      resolvedFindingIds: [],
    })),
    reply: vi.fn(async () => ({
      body: "Thanks, this is a false alarm.",
      verdict: "withdraw" as const,
    })),
    chat: vi.fn(async () => "Private explanation"),
    summarize: vi.fn(async () => [
      {
        kind: "experience" as const,
        body: summaryBody,
      },
    ]),
    test: vi.fn(async () => "ok"),
  };
  const workflow = new Workflow(store, repository, agent);
  const worker = new Worker(store, workflow);
  return {
    store,
    workflow,
    worker,
    repository,
    agent,
    comments,
    setSnapshot: (next: Partial<PullRequestSnapshot>) => {
      snapshot = { ...snapshot, ...next };
    },
  };
}
async function reviewed(f: ReturnType<typeof fixture>) {
  const session = await f.workflow.importPullRequest(project.id, 1);
  const job = f.workflow.queueReview(session.id);
  await f.worker.drain();
  return {
    session,
    job,
    run: f.store.runs.list()[0]!,
    finding: f.store.findings.list()[0]!,
  };
}

async function summaryEvidence(f: ReturnType<typeof fixture>) {
  const { session, run, finding } = await reviewed(f);
  f.workflow.queuePublish(run.id);
  await f.worker.drain();
  f.store.findings.save({ ...f.store.findings.get(finding.id)!, status: "resolved" });
  return session;
}

describe("review workflow", () => {
  it("freezes review inputs, validates positions and grounds evidence, reporting partial coverage", async () => {
    const f = fixture();
    const experience: Experience = {
      id: "e1",
      projectId: project.id,
      kind: "experience",
      body: "Follow validated call sites.",
      sourceSessionIds: ["prior"],
      status: "accepted",
      createdAt: now,
      updatedAt: now,
    };
    f.store.sessions.save({
      ...initialSnapshot,
      id: "prior",
      number: 99,
      projectId: project.id,
      ciStatus: "unknown",
      createdAt: now,
      updatedAt: now,
    });
    f.store.experiences.save(experience);
    f.setSnapshot({
      truncated: true,
      files: [
        ...initialSnapshot.files,
        {
          path: "package.lock",
          patch: "+ignore me",
          status: "modified",
          additions: 1,
          deletions: 0,
        },
      ],
    });
    vi.mocked(f.agent.review).mockResolvedValue({
      summary: "Result",
      findings: [
        draft,
        { ...draft, title: "Invalid position", line: 999 },
        { ...draft, title: "Invented file", path: "secret.ts" },
        { ...draft, title: "No evidence", evidence: "It looks wrong somehow." },
      ],
      resolvedFindingIds: ["invented"],
    });
    const { run } = await reviewed(f);
    expect(run.status).toBe("completed");
    expect(run.experienceSnapshot).toEqual([experience]);
    expect(run.standardSnapshot).toBe(project.standard);
    expect(run.modelSnapshot.model).toBe(model.model);
    expect(run.coverage).toContain("部分覆盖");
    expect(run.coverage).toContain("拒绝无有效依据或定位的发现 3");
    expect(f.store.findings.list()).toHaveLength(1);
    expect(vi.mocked(f.agent.review).mock.calls[0]![0].snapshot.files).toHaveLength(1);
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });

  it("retries a failed review with the same run, configuration, and experience snapshots", async () => {
    const f = fixture();
    vi.useFakeTimers();
    vi.mocked(f.agent.review).mockRejectedValueOnce(new Error("temporary model failure"));
    const { session, run } = await reviewed(f);
    expect(run.status).toBe("failed");
    f.store.projects.save({
      ...project,
      standard: "Changed standard",
      standardVersion: 2,
    });
    f.store.models.save({ ...model, model: "new-model" });
    vi.setSystemTime(Date.now() + 10000);
    await f.worker.drain();
    expect(f.store.runs.list()).toHaveLength(1);
    expect(f.store.runs.list()[0]!.status).toBe("completed");
    const retryInput = vi.mocked(f.agent.review).mock.calls[1]![0];
    expect(retryInput.project.standard).toBe(project.standard);
    expect(retryInput.model.model).toBe("test-model");
    expect(f.store.sessions.list().filter((s) => s.id === session.id)).toHaveLength(1);
  });

  it("does not send a replacement credential to an endpoint frozen before connection changes", async () => {
    const f = fixture();
    vi.useFakeTimers();
    vi.mocked(f.agent.review).mockRejectedValueOnce(
      new Error("first provider temporarily unavailable"),
    );
    await reviewed(f);
    f.store.models.save({
      ...model,
      baseUrl: "https://replacement-provider.test/v1",
    });
    f.store.vault.set(`model:${model.id}`, "new-provider-secret");
    vi.setSystemTime(Date.now() + 10000);
    await f.worker.drain();
    expect(f.agent.review).toHaveBeenCalledTimes(1);
    expect(f.store.runs.list()[0]!.error).toContain("模型连接地址已变更");
  });

  it("does not reuse a frozen review after its ChatGPT account changes", async () => {
    const f = fixture();
    vi.useFakeTimers();
    const now = new Date().toISOString();
    for (const id of ["first", "second"]) {
      f.store.chatgptAccounts.save({
        id,
        clientId: `oaiapp_${id}`,
        subject: id,
        email: `${id}@example.com`,
        createdAt: now,
        updatedAt: now,
      });
    }
    f.store.models.save({
      ...model,
      authMode: "chatgpt",
      chatgptAccountId: "first",
      baseUrl: "https://api.openai.com/v1",
    });
    vi.mocked(f.agent.review).mockRejectedValueOnce(new Error("temporary model failure"));
    await reviewed(f);
    expect(f.store.runs.list()[0]?.modelSnapshot.chatgptAccountId).toBe("first");
    f.store.models.save({ ...f.store.models.get(model.id)!, chatgptAccountId: "second" });
    vi.setSystemTime(Date.now() + 10000);
    await f.worker.drain();
    expect(f.agent.review).toHaveBeenCalledTimes(1);
    expect(f.store.runs.list()[0]?.error).toContain("模型登录方式或账号已变更");
  });

  it("supersedes results when a commit changes during the model request", async () => {
    const f = fixture();
    vi.mocked(f.agent.review).mockImplementationOnce(async () => {
      f.setSnapshot({ headSha: "head2" });
      return { summary: "stale", findings: [draft], resolvedFindingIds: [] };
    });
    const { run } = await reviewed(f);
    expect(run.status).toBe("superseded");
    expect(f.store.findings.list()).toHaveLength(0);
    expect(() => f.workflow.queuePublish(run.id)).toThrow();
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });

  it("checks publication revisions through metadata without rereading all file content", async () => {
    const f = fixture();
    const { run } = await reviewed(f);
    const contentReads = vi.mocked(f.repository.getPullRequest).mock.calls.length;
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    expect(f.repository.getRevision).toHaveBeenCalled();
    expect(vi.mocked(f.repository.getPullRequest).mock.calls.length).toBe(contentReads);
  });

  it("recovers a remotely accepted publication after response failure without duplicate comments", async () => {
    const f = fixture();
    vi.useFakeTimers();
    const { run } = await reviewed(f);
    const original = f.repository.publishComment;
    vi.mocked(f.repository.publishComment).mockImplementationOnce(
      async (p, token, snapshot, comment) => {
        const remote: RemoteComment = {
          id: "review:accepted",
          threadId: "review:accepted",
          body: comment.body,
          author: "agent",
          isBot: true,
          createdAt: now,
        };
        f.comments.push(remote);
        throw new Error("response lost after remote commit");
      },
    );
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    expect(f.comments).toHaveLength(1);
    expect(f.store.publications.list()[0]!.state).toBe("pending");
    vi.setSystemTime(Date.now() + 10000);
    await f.worker.drain();
    expect(f.comments).toHaveLength(2); // one finding, one overview
    expect(f.store.findings.list()[0]!.remoteId).toBe("review:accepted");
    expect(f.store.publications.list().every((p) => p.state === "published")).toBe(true);
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    expect(f.comments).toHaveLength(2);
    expect(original).toHaveBeenCalledTimes(2);
  });

  it("does not reuse an old pending publication body when a finding recurs on a new commit", async () => {
    const f = fixture();
    const { session, run } = await reviewed(f);
    vi.mocked(f.repository.publishComment).mockRejectedValueOnce(
      new Error("offline before publishing"),
    );
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    f.setSnapshot({ headSha: "head2" });
    vi.mocked(f.agent.review).mockResolvedValueOnce({
      summary: "New round",
      findings: [{ ...draft, body: "Updated explanation for new code." }],
      resolvedFindingIds: [],
    });
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    const latest = f.store.runs.list().find((r) => r.headSha === "head2")!;
    f.workflow.queuePublish(latest.id);
    await f.worker.drain();
    expect(f.comments).toHaveLength(2);
    expect(f.comments[0]!.body).toContain("Updated explanation for new code.");
    expect(f.store.runs.get(run.id)!.status).toBe("superseded");
  });

  it("recovers an earlier remotely accepted finding across a newer review round", async () => {
    const f = fixture();
    const { session, run } = await reviewed(f);
    vi.mocked(f.repository.publishComment).mockImplementationOnce(async (_p, _t, _s, comment) => {
      f.comments.push({
        id: "review:old-accepted",
        threadId: "review:old-accepted",
        body: comment.body,
        author: "agent",
        isBot: true,
        createdAt: now,
      });
      throw new Error("response lost");
    });
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    f.setSnapshot({ headSha: "head2" });
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    const latest = f.store.runs.list().find((r) => r.headSha === "head2")!;
    f.workflow.queuePublish(latest.id);
    await f.worker.drain();
    expect(f.comments).toHaveLength(2); // old finding is reconciled; only the new overview is posted
    expect(f.store.findings.list()[0]!.remoteId).toBe("review:old-accepted");
  });

  it("supersedes frozen diffs when the base changes even if the head stays the same", async () => {
    const f = fixture();
    const { run } = await reviewed(f);
    f.workflow.queuePublish(run.id);
    f.setSnapshot({ baseSha: "base2" });
    await f.worker.drain();
    expect(f.store.runs.get(run.id)!.status).toBe("superseded");
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });

  it("never publishes a completed run against a newer remote head", async () => {
    const f = fixture();
    const { run } = await reviewed(f);
    f.workflow.queuePublish(run.id);
    f.setSnapshot({ headSha: "head2" });
    await f.worker.drain();
    expect(f.store.runs.get(run.id)!.status).toBe("superseded");
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });

  it("deduplicates repeated findings while keeping one session across rounds", async () => {
    const f = fixture();
    const { session, finding } = await reviewed(f);
    f.setSnapshot({ headSha: "head2" });
    await f.workflow.importPullRequest(project.id, 1);
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    expect(f.store.sessions.list()).toHaveLength(1);
    expect(f.store.runs.list()).toHaveLength(2);
    expect(f.store.findings.list()).toHaveLength(1);
    expect(f.store.findings.list()[0]!.id).toBe(finding.id);
  });

  it("keeps identical titles at different code locations as distinct findings", async () => {
    const f = fixture();
    vi.mocked(f.agent.review).mockResolvedValueOnce({
      summary: "two locations",
      findings: [draft, { ...draft, line: 1, evidence: "const ready = true;" }],
      resolvedFindingIds: [],
    });
    const { run } = await reviewed(f);
    expect(f.store.findings.list()).toHaveLength(2);
    expect(run.resultSnapshot!.findings).toHaveLength(2);
  });

  it.each(["ignored", "truncated"] as const)(
    "does not resolve old findings when their evidence is %s",
    async (reason) => {
      const f = fixture();
      const { session, finding } = await reviewed(f);
      if (reason === "ignored") {
        f.store.projects.save({ ...project, ignorePatterns: ["src/**"] });
      } else {
        f.setSnapshot({ truncated: true });
      }
      vi.mocked(f.agent.review).mockResolvedValueOnce({
        summary: "claimed fixed",
        findings: [],
        resolvedFindingIds: [finding.id],
      });
      f.workflow.queueReview(session.id);
      await f.worker.drain();
      expect(f.store.findings.get(finding.id)!.status).toBe("open");
      const latest = f.store.runs.list().find((r) => r.id !== f.store.runs.list()[0]!.id)!;
      expect(latest.resultSnapshot!.resolvedFindingIds).toEqual([]);
    },
  );

  it("announces a resolved finding reappearing on a new commit in its original thread", async () => {
    const f = fixture();
    const { session, run, finding } = await reviewed(f);
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    const originalThread = f.store.findings.get(finding.id)!.remoteThreadId;
    f.setSnapshot({ headSha: "head2" });
    vi.mocked(f.agent.review).mockResolvedValueOnce({
      summary: "fixed",
      findings: [],
      resolvedFindingIds: [finding.id],
    });
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    expect(f.store.findings.get(finding.id)!.status).toBe("resolved");
    f.setSnapshot({ headSha: "head3" });
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    expect(f.store.findings.get(finding.id)!.publishedAt).toBeUndefined();
    const latest = f.store.runs.list().find((r) => r.headSha === "head3")!;
    f.workflow.queuePublish(latest.id);
    await f.worker.drain();
    const recurrence = f.comments.find((comment) => comment.body.includes("再次出现"))!;
    expect(recurrence.threadId).toBe(originalThread);
    expect(f.store.findings.get(finding.id)!.status).toBe("open");
    expect(f.store.findings.get(finding.id)!.publishedAt).toBeTruthy();
  });

  it("resolves only findings offered in the current session and leaves unrelated findings intact", async () => {
    const f = fixture();
    const { session, finding, run } = await reviewed(f);
    f.store.sessions.save({ ...session, id: "another-session", number: 2 });
    f.store.runs.save({
      ...run,
      id: "another-run",
      sessionId: "another-session",
      snapshot: { ...initialSnapshot, number: 2 },
      resultSnapshot: undefined,
    });
    f.store.findings.save({
      ...finding,
      id: "other-finding",
      sessionId: "another-session",
      runId: "another-run",
      fingerprint: "another",
    });
    vi.mocked(f.agent.review).mockResolvedValueOnce({
      summary: "fixed",
      findings: [],
      resolvedFindingIds: [finding.id, "other-finding"],
    });
    f.workflow.queueReview(session.id);
    await f.worker.drain();
    expect(f.store.findings.get(finding.id)!.status).toBe("resolved");
    expect(f.store.findings.get("other-finding")!.status).toBe("open");
  });
});

describe("events, discussion, and knowledge", () => {
  it("durably dispatches PR events, deduplicates delivery, and keeps CI updates attached to their SHA", async () => {
    const f = fixture();
    const event = {
      id: "event1",
      type: "pr" as const,
      repository: project.repository,
      number: 1,
      headSha: "head1",
    };
    f.store.jobs.enqueue("event", { projectId: project.id, event }, "event:delivery1");
    await f.worker.drain();
    expect(f.store.runs.list()).toHaveLength(1);
    await f.workflow.acceptEvent(project.id, event);
    await f.worker.drain();
    expect(f.store.runs.list()).toHaveLength(1);
    await f.workflow.acceptEvent(project.id, {
      ...event,
      id: "ci1",
      type: "ci",
      state: "success",
    });
    expect(f.store.sessions.list()[0]!.ciStatus).toBe("success");
    await f.workflow.acceptEvent(project.id, {
      ...event,
      id: "ci2",
      type: "ci",
      state: "failure",
      headSha: "old",
    });
    expect(f.store.sessions.list()[0]!.ciStatus).toBe("success");
    expect(f.store.runs.list()).toHaveLength(1);
    f.setSnapshot({ state: "merged" });
    await f.workflow.acceptEvent(project.id, {
      ...event,
      id: "closed",
      type: "closed",
      state: "merged",
    });
    expect(f.store.sessions.list()[0]!.state).toBe("merged");
  });

  it.each(["closed", "merged"] as const)(
    "keeps the fetched open state and history after a delayed %s event",
    async (state) => {
      const f = fixture();
      const { session, run } = await reviewed(f);
      await f.workflow.acceptEvent(project.id, {
        id: "delayed-close",
        type: "closed",
        repository: project.repository,
        number: session.number,
        state,
      });
      expect(f.store.sessions.list()).toHaveLength(1);
      expect(f.store.sessions.get(session.id)?.state).toBe("open");
      expect(f.store.runs.get(run.id)).toEqual(run);
      f.workflow.queueReview(session.id);
      await f.worker.drain();
      expect(f.store.runs.listBySession(session.id)).toHaveLength(2);
      expect(f.agent.review).toHaveBeenCalledTimes(2);
    },
  );

  it.each(["closed", "merged"] as const)(
    "keeps the fetched %s state and prevents another review",
    async (state) => {
      const f = fixture();
      const { session, run } = await reviewed(f);
      f.setSnapshot({ state });
      await f.workflow.acceptEvent(project.id, {
        id: "current-close",
        type: "closed",
        repository: project.repository,
        number: session.number,
        state,
      });
      expect(f.store.sessions.list()).toHaveLength(1);
      expect(f.store.sessions.get(session.id)?.state).toBe(state);
      expect(f.store.runs.get(run.id)?.status).toBe("superseded");
      expect(() => f.workflow.queueReview(session.id)).toThrow();
      expect(f.agent.review).toHaveBeenCalledTimes(1);
    },
  );

  it("keeps chat private, publishes explicit user text, and ignores its webhook echo", async () => {
    const f = fixture();
    const { session } = await reviewed(f);
    f.workflow.queueMessage(session.id, "Explain the review", "internal");
    await f.worker.drain();
    expect(f.store.messages.list().some((m) => m.body === "Private explanation")).toBe(true);
    expect(f.repository.publishComment).not.toHaveBeenCalled();
    f.store.models.save({ ...model, enabled: false });
    f.workflow.queueMessage(session.id, "Please look at the validation helper.", "external");
    await f.worker.drain();
    expect(f.comments).toHaveLength(1);
    expect(f.comments[0]!.body).toContain("Please look at the validation helper.");
    expect(f.agent.reply).not.toHaveBeenCalled();
    await f.workflow.acceptEvent(project.id, {
      id: "echo",
      type: "reply",
      repository: project.repository,
      number: 1,
      body: f.comments[0]!.body,
      commentId: f.comments[0]!.id,
      threadId: f.comments[0]!.threadId,
    });
    await f.worker.drain();
    expect(f.agent.reply).not.toHaveBeenCalled();
  });

  it("reassesses external feedback in the original thread and produces sourced draft knowledge", async () => {
    const f = fixture();
    const { session, run, finding } = await reviewed(f);
    f.workflow.queueSummary(project.id);
    await f.worker.drain();
    expect(f.agent.summarize).not.toHaveBeenCalled();
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    const published = f.store.findings.get(finding.id)!;
    const feedback = {
      id: "reply1",
      type: "reply" as const,
      repository: project.repository,
      number: 1,
      body: "The caller already validates input.",
      commentId: "review:human",
      threadId: published.remoteThreadId,
    };
    await f.workflow.acceptEvent(project.id, feedback);
    await f.workflow.acceptEvent(project.id, feedback);
    await f.worker.drain();
    expect(f.agent.reply).toHaveBeenCalledTimes(1);
    expect(f.store.findings.get(finding.id)!.status).toBe("withdrawn");
    expect(vi.mocked(f.repository.publishComment).mock.calls.at(-1)![3].threadId).toBe(
      published.remoteThreadId,
    );
    f.workflow.queueSummary(project.id);
    await f.worker.drain();
    const experiences = f.store.experiences.list();
    expect(experiences).toHaveLength(1);
    expect(experiences[0]!.status).toBe("draft");
    expect(experiences[0]!.sourceSessionIds).toEqual([session.id]);
    expect(vi.mocked(f.agent.summarize).mock.calls[0]![0].evidence).toContain(session.url);
    expect(f.store.projects.get(project.id)!.standard).toBe(project.standard);
  });

  it.each([false, true])(
    "retries malformed summaries without saving any part of the batch (valid proposal first: %s)",
    async (validProposalFirst) => {
      vi.useFakeTimers();
      const f = fixture();
      const session = await summaryEvidence(f);
      const validProposal = { kind: "experience" as const, body: summaryBody };
      const invalidProposal = {
        kind: "experience" as const,
        body: "适用范围：External input. 支持证据：One review. 建议：Check callers. 局限：One PR.",
      };
      vi.mocked(f.agent.summarize)
        .mockResolvedValueOnce(
          validProposalFirst ? [validProposal, invalidProposal] : [invalidProposal],
        )
        .mockResolvedValueOnce([validProposal]);
      const job = f.workflow.queueSummary(project.id);

      await f.worker.drain();

      const retry = f.store.jobs.get(job.id)!;
      expect(retry.status).toBe("queued");
      expect(retry.attempts).toBe(1);
      expect(retry.maxAttempts).toBe(3);
      expect(retry.error).toMatch(/^跨 PR 总结分节格式无效：.+/);
      expect(retry.availableAt).toBeGreaterThan(Date.now());
      expect(f.store.experiences.list()).toEqual([]);
      expect(f.agent.summarize).toHaveBeenCalledTimes(1);

      await f.worker.drain();
      expect(f.agent.summarize).toHaveBeenCalledTimes(1);
      vi.setSystemTime(retry.availableAt);
      await f.worker.drain();

      expect(f.store.jobs.get(job.id)).toMatchObject({
        status: "completed",
        attempts: 2,
        error: null,
      });
      expect(f.agent.summarize).toHaveBeenCalledTimes(2);
      expect(f.store.experiences.list()).toEqual([
        expect.objectContaining({
          id: `experience:${job.id}:0`,
          kind: "experience",
          body: summaryBody,
          sourceSessionIds: [session.id],
          status: "draft",
        }),
      ]);
    },
  );

  it("fails a summary after three malformed results without leaving partial drafts", async () => {
    vi.useFakeTimers();
    const f = fixture();
    await summaryEvidence(f);
    vi.mocked(f.agent.summarize).mockResolvedValue([
      { kind: "experience", body: summaryBody },
      { kind: "standard", body: "An unsectioned summary must never become a draft." },
    ]);
    const job = f.workflow.queueSummary(project.id);

    for (let attempt = 1; attempt <= 3; attempt++) {
      await f.worker.drain();
      const result = f.store.jobs.get(job.id)!;
      expect(result.attempts).toBe(attempt);
      expect(result.status).toBe(attempt === 3 ? "failed" : "queued");
      expect(result.error).toMatch(/^跨 PR 总结分节格式无效：.+/);
      expect(f.agent.summarize).toHaveBeenCalledTimes(attempt);
      expect(f.store.experiences.list()).toEqual([]);
      if (attempt < 3) {
        vi.setSystemTime(result.availableAt);
      }
    }

    vi.setSystemTime(Date.now() + 120_000);
    await f.worker.drain();
    expect(f.agent.summarize).toHaveBeenCalledTimes(3);
    expect(f.store.jobs.get(job.id)!.status).toBe("failed");
    expect(f.store.experiences.list()).toEqual([]);
  });

  it("completes a summary with no proposals without retrying or creating drafts", async () => {
    const f = fixture();
    await summaryEvidence(f);
    vi.mocked(f.agent.summarize).mockResolvedValueOnce([]);
    const job = f.workflow.queueSummary(project.id);

    await f.worker.drain();

    expect(f.store.jobs.get(job.id)).toMatchObject({
      status: "completed",
      attempts: 1,
      error: null,
    });
    expect(f.agent.summarize).toHaveBeenCalledTimes(1);
    expect(f.store.experiences.list()).toEqual([]);
  });

  it("resolves GitLab note webhook thread IDs from remote discussions when omitted", async () => {
    const f = fixture();
    f.store.projects.save({ ...project, provider: "gitlab" });
    const { run, finding } = await reviewed(f);
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    const published = f.store.findings.get(finding.id)!;
    f.comments.push({
      id: "note:human",
      threadId: published.remoteThreadId,
      body: "Caller validates this.",
      author: "developer",
      isBot: false,
      createdAt: now,
    });
    await f.workflow.acceptEvent(project.id, {
      id: "gitlab-reply",
      type: "reply",
      repository: project.repository,
      number: 1,
      body: "Caller validates this.",
      commentId: "note:human",
    });
    await f.worker.drain();
    expect(f.agent.reply).toHaveBeenCalledTimes(1);
  });

  it("recovers a reply publication without generating the reply or posting it twice", async () => {
    const f = fixture();
    vi.useFakeTimers();
    const { run, finding } = await reviewed(f);
    f.workflow.queuePublish(run.id);
    await f.worker.drain();
    const published = f.store.findings.get(finding.id)!;
    vi.mocked(f.repository.publishComment).mockImplementationOnce(async (_p, _t, _s, comment) => {
      f.comments.push({
        id: "review:reply-accepted",
        threadId: comment.threadId,
        body: comment.body,
        author: "agent",
        isBot: true,
        createdAt: now,
      });
      throw new Error("reply response lost");
    });
    await f.workflow.acceptEvent(project.id, {
      id: "reply-lost",
      type: "reply",
      repository: project.repository,
      number: 1,
      body: "Validated earlier.",
      commentId: "review:human-lost",
      threadId: published.remoteThreadId,
    });
    await f.worker.drain();
    expect(f.comments).toHaveLength(3);
    vi.setSystemTime(Date.now() + 10000);
    await f.worker.drain();
    expect(f.comments).toHaveLength(3);
    expect(f.agent.reply).toHaveBeenCalledTimes(1);
    expect(
      f.store.messages.list().filter((m) => m.role === "assistant" && m.findingId === finding.id),
    ).toHaveLength(1);
    expect(f.store.messages.list().find((m) => m.role === "assistant")!.remoteId).toBe(
      "review:reply-accepted",
    );
  });

  it("recovers an interrupted durable job after reopening SQLite", async () => {
    const directory = mkdtempSync(join(tmpdir(), "review-workflow-"));
    directories.push(directory);
    const path = join(directory, "review.db");
    const f = fixture(new Store(path));
    const session = await f.workflow.importPullRequest(project.id, 1);
    const queued = f.workflow.queueReview(session.id);
    const claimed = f.store.jobs.claim()!;
    expect(claimed.id).toBe(queued.id);
    f.store.close();
    stores.splice(stores.indexOf(f.store), 1);
    const restored = fixture(new Store(path));
    restored.store.jobs.recover();
    await restored.worker.drain();
    expect(restored.store.jobs.list().find((j) => j.id === queued.id)!.status).toBe("completed");
    expect(restored.store.runs.list()).toHaveLength(1);
    expect(restored.store.runs.list()[0]!.id).toBe(`run:${queued.id}`);
    expect(restored.store.vault.get(`model:${model.id}`)).toBe("model-secret");
  });
});

it("parses new-side line positions across diff hunks", () => {
  expect([
    ...diffLines("@@ -1,3 +1,3 @@\n context\n-old\n+new\n rest\n@@ -8 +9,2 @@\n+++value\n+end"),
  ]).toEqual([1, 2, 3, 9, 10]);
});
