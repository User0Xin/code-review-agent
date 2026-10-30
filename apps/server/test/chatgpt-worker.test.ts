import { afterEach, describe, expect, it, vi } from "vitest";
import { AiReviewAgent } from "../src/integrations/agent.js";
import { ChatGptAuthService } from "../src/integrations/chatgpt-auth.js";
import { Worker } from "../src/jobs/worker.js";
import type { RepositoryPort } from "../src/ports.js";
import { Store } from "../src/store.js";
import { Workflow } from "../src/workflow/workflow.js";
import {
  fixtureModel,
  fixtureSnapshot,
  fixtureTime,
  seedWorkspace,
} from "./fixtures/stored-workspace.js";

const accountId = "worker-account";
const accessToken = "private-worker-access-token";
const refreshToken = "private-worker-refresh-token";
const providerValue = "private-worker-provider-response";
const fixtures: Array<{ store: Store; auth: ChatGptAuthService }> = [];

afterEach(() => {
  for (const { store, auth } of fixtures.splice(0)) {
    auth.onModuleDestroy();
    store.close();
  }
  vi.useRealTimers();
});

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function fixture(refresh: () => Promise<Response>, withCredentials = true) {
  const store = new Store(":memory:");
  seedWorkspace(store);
  store.chatgptAccounts.save({
    id: accountId,
    email: "worker@example.test",
    subject: "worker-subject",
    clientId: "worker-client",
    createdAt: fixtureTime,
    updatedAt: fixtureTime,
  });
  if (withCredentials) {
    store.vault.set(
      `chatgpt:${accountId}`,
      JSON.stringify({
        accessToken,
        refreshToken,
        idToken: "private-worker-id-token",
        expiresAt: Date.now() - 1000,
        scopes: ["chatgpt.tokens.use.direct"],
      }),
    );
  }
  store.models.save({
    ...fixtureModel,
    authMode: "chatgpt",
    chatgptAccountId: accountId,
    baseUrl: "https://api.openai.com/v1",
  });
  const refreshRequest = vi.fn(refresh);
  const auth = new ChatGptAuthService(store, {
    fetch: async (input) => {
      expect(String(input)).toBe("https://auth.openai.com/api/accounts/oauth/token");
      return refreshRequest();
    },
    verificationKey: async () => {
      throw new Error(providerValue);
    },
  });
  fixtures.push({ store, auth });
  const inference = vi.fn<typeof fetch>(async () => {
    throw new Error("The model must not be called before credentials are refreshed");
  });
  const agent = new AiReviewAgent({
    fetch: inference,
    chatgptAccessToken: (id) => auth.accessToken(id),
  });
  const repository: RepositoryPort = {
    getPullRequest: vi.fn(async () => fixtureSnapshot),
    getRevision: vi.fn(async () => fixtureSnapshot),
    listPullRequests: vi.fn(async () => []),
    getComments: vi.fn(async () => []),
    publishComment: vi.fn(async () => {
      throw new Error("This fixture must never publish comments");
    }),
  };
  const workflow = new Workflow(store, repository, agent);
  const worker = new Worker(store, workflow);
  const job = workflow.queueReview("s1");
  return { store, worker, job, refreshRequest, inference };
}

function expectSafe(error: string | null | undefined) {
  expect(error).toBeTruthy();
  expect(error).not.toContain(accessToken);
  expect(error).not.toContain(refreshToken);
  expect(error).not.toContain(providerValue);
}

describe("ChatGPT credentials in durable review jobs", () => {
  it.each([408, 429, 503])(
    "preserves bounded retries when token refresh returns HTTP %i",
    async (status) => {
      vi.useFakeTimers();
      const f = fixture(async () =>
        json({ error: "temporarily_unavailable", error_description: providerValue }, status),
      );

      await f.worker.drain();

      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "queued", attempts: 1 });
      expectSafe(f.store.jobs.get(f.job.id)?.error);
      await vi.advanceTimersByTimeAsync(2000);
      await f.worker.drain();
      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "queued", attempts: 2 });
      await vi.advanceTimersByTimeAsync(4000);
      await f.worker.drain();
      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "failed", attempts: 3 });
      expect(f.refreshRequest).toHaveBeenCalledTimes(3);
      expect(f.inference).not.toHaveBeenCalled();
      expect(f.store.vault.get(`chatgpt:${accountId}`)).not.toBe("");
    },
  );

  it.each([
    { name: "network", error: new TypeError(`fetch failed ${providerValue}`) },
    {
      name: "timeout",
      error: new DOMException(`request timed out ${providerValue}`, "TimeoutError"),
    },
  ])(
    "retries a token refresh $name failure without exposing transport details",
    async ({ error }) => {
      vi.useFakeTimers();
      const f = fixture(async () => {
        throw error;
      });

      await f.worker.drain();

      expect(f.store.jobs.get(f.job.id)).toMatchObject({
        status: "queued",
        attempts: 1,
        error: "ChatGPT 授权请求失败，请重新登录或检查网络",
      });
      expectSafe(f.store.jobs.get(f.job.id)?.error);
      await vi.advanceTimersByTimeAsync(2000);
      await f.worker.drain();
      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "queued", attempts: 2 });
      expect(f.refreshRequest).toHaveBeenCalledTimes(2);
      expect(f.inference).not.toHaveBeenCalled();
    },
  );

  it.each([
    { status: 400, code: "invalid_grant", error: "ChatGPT 授权已失效，请重新登录" },
    {
      status: 401,
      code: "invalid_client",
      error: "ChatGPT 授权未获接受，请重新登录或检查客户端配置",
    },
    { status: 403, code: "access_denied", error: "ChatGPT 权限不足，请检查账号授权与额度设置" },
  ])(
    "stops an HTTP $status $code refresh rejection on the first attempt",
    async ({ status, code, error }) => {
      vi.useFakeTimers();
      const f = fixture(async () =>
        json({ error: code, error_description: providerValue }, status),
      );

      await f.worker.drain();
      await vi.advanceTimersByTimeAsync(60_000);
      await f.worker.drain();

      expect(f.store.jobs.get(f.job.id)).toMatchObject({ status: "failed", attempts: 1, error });
      expectSafe(f.store.jobs.get(f.job.id)?.error);
      expect(f.refreshRequest).toHaveBeenCalledTimes(1);
      expect(f.inference).not.toHaveBeenCalled();
      if (code === "invalid_grant") {
        expect(f.store.vault.get(`chatgpt:${accountId}`)).toBe("");
      }
    },
  );

  it("stops a missing account credential before attempting refresh or inference", async () => {
    const f = fixture(async () => json({}), false);

    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 1,
      error: "ChatGPT 账号未登录或授权已失效，请重新登录",
    });
    expect(f.refreshRequest).not.toHaveBeenCalled();
    expect(f.inference).not.toHaveBeenCalled();
  });

  it("keeps malformed refresh responses terminal without exposing their contents", async () => {
    const f = fixture(async () => json({ private: providerValue, access_token: accessToken }));

    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 1,
      error: "ChatGPT 授权请求失败，请重新登录或检查网络",
    });
    expectSafe(f.store.jobs.get(f.job.id)?.error);
    expect(f.refreshRequest).toHaveBeenCalledTimes(1);
    expect(f.inference).not.toHaveBeenCalled();
  });

  it("keeps refresh identity validation failures terminal", async () => {
    const f = fixture(async () =>
      json({
        access_token: accessToken,
        id_token: providerValue,
        token_type: "Bearer",
        expires_in: 3600,
      }),
    );

    await f.worker.drain();

    expect(f.store.jobs.get(f.job.id)).toMatchObject({
      status: "failed",
      attempts: 1,
      error: "ChatGPT 身份验证失败，请重新登录",
    });
    expectSafe(f.store.jobs.get(f.job.id)?.error);
    expect(f.refreshRequest).toHaveBeenCalledTimes(1);
    expect(f.inference).not.toHaveBeenCalled();
  });
});
