import type {
  Job,
  ModelConnection,
  Project,
  PullRequestSnapshot,
  Session,
  SessionDetail,
} from "@review/contracts";
import { createHmac } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { AiReviewAgent } from "../src/integrations/agent.js";
import { IntegrationError } from "../src/integrations/integration-error.js";
import type { AgentPort, RepositoryPort } from "../src/ports.js";
import { Store } from "../src/store.js";

const cleanup: Array<() => Promise<void>> = [];
afterEach(async () => {
  for (const close of cleanup.splice(0).reverse()) {
    await close();
  }
});
async function fixture() {
  const store = new Store(":memory:");
  const snapshot: PullRequestSnapshot = {
    number: 7,
    title: "Handle requests",
    description: "Validate the incoming request",
    url: "https://github.com/team/repo/pull/7",
    headSha: "head7",
    baseSha: "base7",
    branch: "feature",
    baseBranch: "dev",
    state: "open",
    truncated: false,
    files: [
      {
        path: "src/request.ts",
        patch: "@@ -1 +1,2 @@\n const ready = true;\n+const user = input;",
        content: "const ready = true;\nconst user = input;",
        status: "modified",
        additions: 1,
        deletions: 0,
      },
    ],
  };
  const repository: RepositoryPort = {
    getPullRequest: vi.fn(async () => snapshot),
    listPullRequests: vi.fn(async () => [snapshot]),
    getComments: vi.fn(async () => []),
    publishComment: vi.fn(async (_p, _t, _s, c) => ({
      id: "review:1",
      body: c.body,
      author: "agent",
      isBot: true,
      createdAt: new Date().toISOString(),
    })),
  };
  const agent: AgentPort = {
    review: vi.fn<AgentPort["review"]>(async () => ({
      summary: "Review completed",
      findings: [
        {
          title: "Unchecked input",
          body: "Validate before use",
          path: "src/request.ts",
          line: 2,
          severity: "high",
          category: "security",
          evidence: "const user = input;",
        },
      ],
      resolvedFindingIds: [],
    })),
    reply: vi.fn<AgentPort["reply"]>(async () => ({ body: "Acknowledged", verdict: "keep" })),
    chat: vi.fn(async () => "Internal explanation"),
    summarize: vi.fn(async () => []),
    test: vi.fn(async () => "Connected"),
  };
  const runtime = await createApp({
    store,
    repository,
    agent,
    startWorker: false,
  });
  await runtime.app.listen(0, "127.0.0.1");
  cleanup.push(runtime.close);
  const base = await runtime.app.getUrl();
  async function request(
    path: string,
    body?: unknown,
    method = body === undefined ? "GET" : "POST",
    extraHeaders: Record<string, string> = {},
  ) {
    const response = await fetch(`${base}/api${path}`, {
      method,
      headers: { "content-type": "application/json", ...extraHeaders },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data: unknown = await response.json();
    return { status: response.status, data };
  }
  const modelInput = {
    name: "Local model",
    baseUrl: "http://127.0.0.1:9999/v1",
    model: "review-model",
    apiKey: "model-secret",
    enabled: true,
  };
  const model = (await request("/models", modelInput)).data as ModelConnection;
  const projectInput = {
    name: "Repository",
    provider: "github",
    host: "https://github.com",
    repository: "team/repo",
    modelId: model.id,
    token: "repository-secret",
    webhookSecret: "webhook-secret",
    standard: "Validate all external input.",
    ignorePatterns: [],
    autoReview: true,
    autoPublish: false,
  };
  const project = (await request("/projects", projectInput)).data as Project;
  return {
    ...runtime,
    store,
    request,
    repository,
    agent,
    model,
    project,
    modelInput,
    projectInput,
  };
}

describe("HTTP application", () => {
  it("links account models, rejects unauthorised configurations and clears an API key when switching", async () => {
    const f = await fixture();
    const now = new Date().toISOString();
    f.store.chatgptAccounts.save({
      id: "account",
      clientId: "oaiapp_test",
      subject: "subject",
      email: "user@example.com",
      createdAt: now,
      updatedAt: now,
    });
    const accounts = vi
      .spyOn(f.services.chatgpt, "accounts")
      .mockReturnValue([{ id: "account", email: "user@example.com", connected: true }]);
    const input = {
      ...f.modelInput,
      authMode: "chatgpt",
      chatgptAccountId: "account",
      baseUrl: "https://api.openai.com/v1",
      apiKey: undefined,
    };
    for (const invalid of [
      { ...input, chatgptAccountId: undefined },
      { ...input, chatgptAccountId: "unknown" },
      { ...input, baseUrl: "https://attacker.test/v1" },
      { ...input, apiKey: "should-never-be-saved" },
      { ...input, authMode: "api-key" },
    ]) {
      expect((await f.request("/models", invalid)).status).toBe(400);
    }
    const updated = await f.request(`/models/${f.model.id}`, input, "PATCH");
    expect(updated.status).toBe(200);
    expect(updated.data).toMatchObject({
      authMode: "chatgpt",
      chatgptAccountId: "account",
      hasApiKey: false,
    });
    expect(f.store.vault.get(`model:${f.model.id}`)).toBe("");
    expect(f.store.models.get(f.model.id)?.chatgptAccountId).toBe("account");
    accounts.mockReturnValue([{ id: "account", email: "user@example.com", connected: false }]);
    expect((await f.request("/models", input)).status).toBe(400);
    expect(
      (await f.request(`/models/${f.model.id}`, { ...input, enabled: false }, "PATCH")).status,
    ).toBe(200);
    expect((await f.request(`/models/${f.model.id}`, input, "PATCH")).status).toBe(400);
    expect((await f.request(`/models/${f.model.id}`, f.modelInput, "PATCH")).data).toMatchObject({
      authMode: "api-key",
      hasApiKey: true,
    });
    expect(f.store.models.get(f.model.id)?.chatgptAccountId).toBeUndefined();
  });

  it("routes account login, polling, cancellation, model listing and sign out without returning credentials", async () => {
    const f = await fixture();
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    vi.spyOn(f.services.chatgpt, "startLogin").mockResolvedValue({
      id: "attempt",
      status: "pending",
      authorizationUrl: "https://auth.openai.com/api/accounts/authorize",
      expiresAt,
    });
    vi.spyOn(f.services.chatgpt, "loginStatus").mockReturnValue({
      id: "attempt",
      status: "pending",
      expiresAt,
    });
    vi.spyOn(f.services.chatgpt, "cancelLogin").mockReturnValue({
      id: "attempt",
      status: "cancelled",
      expiresAt,
    });
    vi.spyOn(f.services.chatgpt, "models").mockResolvedValue([
      { id: "review-model", name: "Review model" },
    ]);
    vi.spyOn(f.services.chatgpt, "signOut").mockResolvedValue({ message: "已退出" });
    expect((await f.request("/chatgpt/login", {})).status).toBe(201);
    expect((await f.request("/chatgpt/login/attempt")).data).not.toHaveProperty("authorizationUrl");
    expect((await f.request("/chatgpt/login/attempt", undefined, "DELETE")).data).toMatchObject({
      status: "cancelled",
    });
    expect((await f.request("/chatgpt/accounts/account/models")).data).toEqual([
      { id: "review-model", name: "Review model" },
    ]);
    expect(
      (await f.request("/chatgpt/accounts/account/session", undefined, "DELETE")).data,
    ).toEqual({ message: "已退出" });
    expect(
      (await f.request("/chatgpt/login", {}, "POST", { origin: "https://untrusted.test" })).status,
    ).toBe(403);
  });

  it("can shut down while the browser has an active SSE connection", async () => {
    const runtime = await createApp({
      store: new Store(":memory:"),
      startWorker: false,
    });
    await runtime.app.listen(0, "127.0.0.1");
    const controller = new AbortController();
    const response = await fetch(`${await runtime.app.getUrl()}/api/events`, {
      signal: controller.signal,
    });
    expect(response.status).toBe(200);
    const reader = response.body!.getReader();
    await reader.read();
    try {
      await runtime.close();
    } finally {
      controller.abort();
      await reader.cancel().catch(() => {});
    }
  }, 3000);
  it("wires model, repository, review, explicit publication and private conversation end to end", async () => {
    const f = await fixture();
    expect(f.model.id).toBeTruthy();
    expect(f.project.id).toBeTruthy();
    expect((await f.request(`/models/${f.model.id}/test`, {})).data).toEqual({
      message: "Connected",
    });
    const session = (await f.request(`/projects/${f.project.id}/pulls`, { number: 7 }))
      .data as Session;
    expect(session.number).toBe(7);
    expect((await f.request(`/sessions/${session.id}/review`, {})).status).toBe(201);
    await f.services.worker.drain();
    const detail = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
    expect(detail.runs[0]?.status).toBe("completed");
    expect(detail.findings).toHaveLength(1);
    expect(f.repository.publishComment).not.toHaveBeenCalled();
    await f.request(`/runs/${detail.runs[0]!.id}/publish`, {});
    await f.services.worker.drain();
    expect(f.repository.publishComment).toHaveBeenCalledTimes(2);
    await f.request(`/sessions/${session.id}/messages`, {
      body: "Explain the finding",
    });
    await f.services.worker.drain();
    const next = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
    expect(next.messages.some((m) => m.body === "Internal explanation")).toBe(true);
    expect(f.repository.publishComment).toHaveBeenCalledTimes(2);
  });
  it("allows another review of the same commit and returns its specific model failure in the session", async () => {
    const f = await fixture();
    const session = (await f.request(`/projects/${f.project.id}/pulls`, { number: 7 }))
      .data as Session;
    await f.request(`/sessions/${session.id}/review`, {});
    await f.services.worker.drain();

    const rateLimitedAgent = new AiReviewAgent({
      fetch: async () =>
        new Response(
          JSON.stringify({ error: { message: "private-provider-response model-secret" } }),
          { status: 429, headers: { "content-type": "application/json" } },
        ),
    });
    vi.mocked(f.agent.review).mockImplementation((input) => rateLimitedAgent.review(input));
    expect((await f.request(`/sessions/${session.id}/review`, {})).status).toBe(201);
    await f.services.worker.drain();

    const detail = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
    expect(detail.runs).toHaveLength(2);
    expect(detail.runs[0]).toMatchObject({ status: "completed", headSha: "head7" });
    expect(detail.runs[1]).toMatchObject({ status: "failed", headSha: "head7" });
    expect(detail.runs[1]?.error).toContain("HTTP 429");
    expect(f.store.jobs.list().some((job) => job.error === detail.runs[1]?.error)).toBe(true);
    expect(JSON.stringify(detail)).not.toContain("private-provider-response");
    expect(JSON.stringify(detail)).not.toContain("model-secret");
  });
  it.each(["", " \t\n "])(
    "rejects publication before enqueueing when the repository token is empty or blank (%j)",
    async (token) => {
      const f = await fixture();
      const session = (await f.request(`/projects/${f.project.id}/pulls`, { number: 7 }))
        .data as Session;
      await f.request(`/sessions/${session.id}/review`, {});
      await f.services.worker.drain();
      const detail = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
      const jobs = f.store.jobs.list();
      f.store.vault.set(`repo:${f.project.id}`, token);

      const response = await f.request(`/runs/${detail.runs[0]!.id}/publish`, {});

      expect(response).toEqual({
        status: 400,
        data: { message: "发布需要仓库访问令牌，请在仓库配置中填写具有评论权限的令牌" },
      });
      expect(f.store.jobs.list()).toEqual(jobs);
      expect(f.repository.publishComment).not.toHaveBeenCalled();
    },
  );
  it("allows public review and private chat without a token but rejects external messages", async () => {
    const f = await fixture();
    f.store.vault.set(`repo:${f.project.id}`, "");
    const imported = await f.request(`/projects/${f.project.id}/pulls`, { number: 7 });
    expect(imported.status).toBe(201);
    const session = imported.data as Session;
    expect((await f.request(`/sessions/${session.id}/review`, {})).status).toBe(201);
    await f.services.worker.drain();
    expect(
      (await f.request(`/sessions/${session.id}/messages`, { body: "Explain this review" })).status,
    ).toBe(201);
    await f.services.worker.drain();
    const before = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
    expect(before.runs[0]?.status).toBe("completed");
    expect(before.messages.some((message) => message.body === "Internal explanation")).toBe(true);
    const jobs = f.store.jobs.list();

    const response = await f.request(`/sessions/${session.id}/messages`, {
      body: "Please check this finding",
      visibility: "external",
    });

    expect(response).toEqual({
      status: 400,
      data: { message: "发布需要仓库访问令牌，请在仓库配置中填写具有评论权限的令牌" },
    });
    expect(f.store.jobs.list()).toEqual(jobs);
    const after = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
    expect(after.messages).toEqual(before.messages);
    expect(f.repository.publishComment).not.toHaveBeenCalled();
  });
  it.each(["publish", "reply"] as const)(
    "checks repository credentials before retrying a failed %s job",
    async (kind) => {
      const f = await fixture();
      const session = (await f.request(`/projects/${f.project.id}/pulls`, { number: 7 }))
        .data as Session;
      await f.request(`/sessions/${session.id}/review`, {});
      await f.services.worker.drain();
      const detail = (await f.request(`/sessions/${session.id}`)).data as SessionDetail;
      const queued =
        kind === "publish"
          ? await f.request(`/runs/${detail.runs[0]!.id}/publish`, {})
          : await f.request(`/sessions/${session.id}/messages`, {
              body: "Please check this finding",
              visibility: "external",
            });
      const job = queued.data as Job;
      f.store.vault.set(`repo:${f.project.id}`, "");
      expect(await f.request(`/jobs/${job.id}/retry`, {})).toEqual({
        status: 400,
        data: { message: "只能重试失败任务" },
      });
      let time = Date.now();
      const clock = vi.spyOn(Date, "now");
      try {
        for (let attempt = 0; attempt < job.maxAttempts; attempt++) {
          clock.mockReturnValue(time);
          f.store.jobs.claim();
          f.store.jobs.fail(job.id, new IntegrationError("Repository unavailable", true, 503));
          time += 60_000;
        }
      } finally {
        clock.mockRestore();
      }
      const failed = f.store.jobs.get(job.id);
      expect(failed?.status).toBe("failed");

      expect(await f.request(`/jobs/${job.id}/retry`, {})).toEqual({
        status: 400,
        data: { message: "发布需要仓库访问令牌，请在仓库配置中填写具有评论权限的令牌" },
      });
      expect(f.store.jobs.get(job.id)).toEqual(failed);
      f.store.vault.set(`repo:${f.project.id}`, "replacement-test-token");
      const retried = await f.request(`/jobs/${job.id}/retry`, {});
      expect(retried.status).toBe(201);
      expect(retried.data).toMatchObject({ id: job.id, status: "queued", attempts: 0 });
    },
  );
  it("never returns credentials and validates repository identity and coding standard version", async () => {
    const f = await fixture();
    const state = await f.request("/workspace");
    const serialized = JSON.stringify(state.data);
    for (const secret of ["model-secret", "repository-secret", "webhook-secret"]) {
      expect(serialized).not.toContain(secret);
    }
    expect(f.store.vault.get(`repo:${f.project.id}`)).toBe("repository-secret");
    const { token, webhookSecret, ...edit } = f.projectInput;
    const updated = (
      await f.request(
        `/projects/${f.project.id}`,
        { ...edit, standard: "Use strict validation." },
        "PATCH",
      )
    ).data as Project;
    expect(updated.standardVersion).toBe(2);
    expect(updated.hasToken).toBe(true);
    expect(updated.hasWebhookSecret).toBe(true);
    expect(
      (await f.request(`/projects/${f.project.id}`, { ...edit, repository: "team/other" }, "PATCH"))
        .status,
    ).toBe(409);
    expect((await f.request(`/models/${f.model.id}`, undefined, "DELETE")).status).toBe(409);
    expect(
      (
        await f.request("/models", {
          ...f.modelInput,
          baseUrl: "https://user:secret@models.test/v1",
        })
      ).status,
    ).toBe(400);
    expect(
      (
        await f.request("/models", f.modelInput, "POST", {
          origin: "https://untrusted.test",
        })
      ).status,
    ).toBe(403);
  });
  it("authenticates raw webhook bytes, durably accepts once and processes asynchronously", async () => {
    const f = await fixture();
    const payload = {
      action: "opened",
      repository: { full_name: "Team/Repo" },
      pull_request: { number: 7, head: { sha: "head7" }, state: "open" },
    };
    const signature = `sha256=${createHmac("sha256", "webhook-secret").update(JSON.stringify(payload)).digest("hex")}`;
    const path = `/webhooks/github/${f.project.id}`;
    const headers = {
      "x-github-event": "pull_request",
      "x-github-delivery": "delivery-7",
      "x-hub-signature-256": signature,
    };
    expect((await f.request(path, payload)).status).toBe(401);
    expect((await f.request(path, payload, "POST", headers)).data).toEqual({
      accepted: 1,
    });
    expect(f.repository.getPullRequest).not.toHaveBeenCalled();
    expect((await f.request(path, payload, "POST", headers)).data).toEqual({
      accepted: 0,
    });
    const pending = f.store.jobs.list();
    expect(pending).toHaveLength(1);
    expect(pending[0]?.kind).toBe("event");
    await f.services.worker.drain();
    expect(f.store.sessions.list()).toHaveLength(1);
    expect(f.store.runs.list()).toHaveLength(1);
    const tampered = await f.request(path, { ...payload, action: "synchronize" }, "POST", headers);
    expect(tampered.status).toBe(401);
  });
  it("keeps unexpected errors private and preserves explicit not-found responses", async () => {
    const fixtureApp = await fixture();
    vi.mocked(fixtureApp.agent.test).mockRejectedValueOnce(
      new Error("unexpected repository-secret"),
    );
    const response = await fixtureApp.request(`/models/${fixtureApp.model.id}/test`, {});
    expect(response).toEqual({ status: 500, data: { message: "服务内部错误" } });
    expect((await fixtureApp.request("/sessions/missing")).status).toBe(404);
  });

  it("only applies a standard proposal once and keeps rejected experience inactive", async () => {
    const f = await fixture();
    const now = new Date().toISOString();
    f.store.experiences.save({
      id: "proposal",
      projectId: f.project.id,
      kind: "standard",
      body: "Cover boundary cases.",
      sourceSessionIds: [],
      status: "draft",
      createdAt: now,
      updatedAt: now,
    });
    expect((await f.request("/experiences/proposal", { status: "accepted" }, "PATCH")).status).toBe(
      200,
    );
    expect(f.store.projects.get(f.project.id)?.standardVersion).toBe(2);
    expect(f.store.projects.get(f.project.id)?.standard).toContain("Cover boundary cases.");
    expect((await f.request("/experiences/proposal", { status: "accepted" }, "PATCH")).status).toBe(
      409,
    );
    f.store.experiences.save({
      id: "experience",
      projectId: f.project.id,
      kind: "experience",
      body: "Irrelevant proposal",
      sourceSessionIds: [],
      status: "draft",
      createdAt: now,
      updatedAt: now,
    });
    await f.request("/experiences/experience", { status: "rejected" }, "PATCH");
    expect(f.store.experiences.get("experience")?.status).toBe("rejected");
  });
});
