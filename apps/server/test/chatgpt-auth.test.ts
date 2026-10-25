import { createHash } from "node:crypto";
import { exportJWK, generateKeyPair, SignJWT } from "jose";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { ChatGptAuthService, type ChatGptAuthOptions } from "../src/integrations/chatgpt-auth.js";
import { Store } from "../src/store.js";

const issuer = "https://auth.openai.com";
const clientId = "oaiapp_review_test";
const grantedScope =
  "openid email profile offline_access resource.invoke chatgpt.tokens.use.direct";
const accessToken = "private-access-token";
const refreshToken = "private-refresh-token";
let keys: Awaited<ReturnType<typeof generateKeyPair>>;
let jwk: Awaited<ReturnType<typeof exportJWK>>;
const fixtures: Array<{ service: ChatGptAuthService; store: Store }> = [];

beforeAll(async () => {
  keys = await generateKeyPair("RS256", { extractable: true });
  jwk = { ...(await exportJWK(keys.publicKey)), kid: "test-key", use: "sig", alg: "RS256" };
});

afterEach(() => {
  for (const fixture of fixtures.splice(0)) {
    fixture.service.onModuleDestroy();
    fixture.store.close();
  }
});

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function setup(options: ChatGptAuthOptions = {}) {
  let nonce = "";
  let audience = clientId;
  let subject = "account-subject";
  let scopes = grantedScope;
  const requests: Array<{ url: string; body: URLSearchParams }> = [];
  let tokenHook: ((body: URLSearchParams) => Promise<Response>) | undefined;
  let modelsResponse = json({
    models: [
      { slug: "review-model", display_name: "Review model", visibility: "list" },
      { slug: "internal-model", display_name: "Hidden", visibility: "hidden" },
    ],
  });
  const fetcher = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input);
    const body = new URLSearchParams(String(init?.body ?? ""));
    requests.push({ url, body });
    if (url.endsWith("/.well-known/jwks.json")) {
      return json({ keys: [jwk] });
    }
    if (url.endsWith("/oauth/revoke")) {
      return new Response(null, { status: 200 });
    }
    if (url.endsWith("/v1/models")) {
      return modelsResponse;
    }
    if (url.endsWith("/oauth/token")) {
      if (tokenHook) {
        return tokenHook(body);
      }
      const idToken = await new SignJWT({ email: "review@example.com", nonce })
        .setProtectedHeader({ alg: "RS256", kid: "test-key" })
        .setIssuer(issuer)
        .setSubject(subject)
        .setAudience(audience)
        .setIssuedAt()
        .setExpirationTime("1h")
        .sign(keys.privateKey);
      return json({
        access_token: accessToken,
        refresh_token: refreshToken,
        id_token: idToken,
        token_type: "Bearer",
        expires_in: 3600,
        scope: scopes,
      });
    }
    throw new Error("Unexpected private endpoint");
  });
  const store = new Store(":memory:");
  const service = new ChatGptAuthService(store, { fetch: fetcher, ...options });
  fixtures.push({ service, store });
  async function start(accountId?: string) {
    const attempt = await service.startLogin(accountId);
    const authorization = new URL(attempt.authorizationUrl!);
    nonce = authorization.searchParams.get("nonce")!;
    return { attempt, authorization };
  }
  async function callback(authorization: URL, changes: Record<string, string> = {}) {
    const callbackUrl = new URL(authorization.searchParams.get("redirect_uri")!);
    callbackUrl.searchParams.set("code", "private-authorization-code");
    callbackUrl.searchParams.set("state", authorization.searchParams.get("state")!);
    callbackUrl.searchParams.set("client_id", clientId);
    for (const [key, value] of Object.entries(changes)) {
      callbackUrl.searchParams.set(key, value);
    }
    const response = await fetch(callbackUrl);
    await response.text();
    return response;
  }
  async function login() {
    const { attempt, authorization } = await start();
    await callback(authorization);
    const status = service.loginStatus(attempt.id);
    expect(status.status).toBe("completed");
    return status.account!;
  }
  function expire(accountId: string) {
    const saved: Record<string, unknown> = JSON.parse(store.vault.get(`chatgpt:${accountId}`));
    saved.expiresAt = Date.now() - 1000;
    store.vault.set(`chatgpt:${accountId}`, JSON.stringify(saved));
  }
  return {
    service,
    store,
    fetcher,
    requests,
    start,
    callback,
    login,
    expire,
    setNonce: (value: string) => {
      nonce = value;
    },
    setAudience: (value: string) => {
      audience = value;
    },
    setSubject: (value: string) => {
      subject = value;
    },
    setScopes: (value: string) => {
      scopes = value;
    },
    setTokenHook: (hook: (body: URLSearchParams) => Promise<Response>) => {
      tokenHook = hook;
    },
    setModels: (response: Response) => {
      modelsResponse = response;
    },
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("ChatGPT OAuth", () => {
  it("binds PKCE, state, nonce and the issued client to verified identity and protected credentials", async () => {
    const fixture = setup();
    const { attempt, authorization } = await fixture.start();
    expect(authorization.origin).toBe(issuer);
    expect(authorization.searchParams.get("client_id")).toBe("dynamic_agent_client");
    expect(authorization.searchParams.get("agent_name_hint")).toBe("SparkShield");
    expect(authorization.searchParams.get("resource")).toBe("https://api.openai.com/v1");
    expect(authorization.searchParams.get("scope")).toBe(
      grantedScope.replace("openid email profile", "openid profile email"),
    );
    expect(authorization.searchParams.get("redirect_uri")).toMatch(
      /^http:\/\/127\.0\.0\.1:\d+\/auth\/callback$/,
    );
    await fixture.callback(authorization);
    const completed = fixture.service.loginStatus(attempt.id);
    expect(completed.status).toBe("completed");
    expect(completed.authorizationUrl).toBeUndefined();
    expect(completed.account).toEqual({
      id: expect.any(String),
      email: "review@example.com",
      connected: true,
    });
    expect(JSON.stringify(completed)).not.toContain(accessToken);
    const exchange = fixture.requests.find((request) => request.url.endsWith("/oauth/token"))!;
    expect(exchange.body.get("client_id")).toBe(clientId);
    expect(exchange.body.get("redirect_uri")).toBe(authorization.searchParams.get("redirect_uri"));
    expect(
      createHash("sha256").update(exchange.body.get("code_verifier")!).digest("base64url"),
    ).toBe(authorization.searchParams.get("code_challenge"));
    const registration = fixture.store.chatgptAccounts.get(completed.account!.id)!;
    expect(registration).toMatchObject({
      clientId,
      subject: "account-subject",
      email: "review@example.com",
    });
    expect(JSON.stringify(registration)).not.toContain(accessToken);
    expect(await fixture.service.accessToken(registration.id)).toBe(accessToken);
    expect(fixture.requests.some((request) => request.url.endsWith("/.well-known/jwks.json"))).toBe(
      true,
    );
  });

  it("does not consume a pending attempt on invalid state", async () => {
    const fixture = setup();
    const { attempt, authorization } = await fixture.start();
    const rejected = await fixture.callback(authorization, { state: "wrong-state" });
    expect(rejected.status).toBe(400);
    expect(fixture.service.loginStatus(attempt.id).status).toBe("pending");
    expect(fixture.requests).toHaveLength(0);
    await fixture.callback(authorization);
    expect(fixture.service.loginStatus(attempt.id).status).toBe("completed");
  });

  it.each(["nonce", "audience", "permission"])(
    "rejects invalid %s without saving credentials",
    async (mismatch) => {
      const fixture = setup();
      const { attempt, authorization } = await fixture.start();
      if (mismatch === "nonce") {
        fixture.setNonce("wrong-nonce");
      }
      if (mismatch === "audience") {
        fixture.setAudience("another-client");
      }
      if (mismatch === "permission") {
        fixture.setScopes("openid email profile");
      }
      await fixture.callback(authorization);
      expect(fixture.service.loginStatus(attempt.id)).toMatchObject({
        status: "failed",
        error: expect.any(String),
      });
      expect(fixture.service.accounts()).toEqual([expect.objectContaining({ connected: false })]);
      const saved = fixture.store.chatgptAccounts.list()[0]!;
      if (mismatch === "permission") {
        expect(fixture.store.vault.get(`chatgpt:${saved.id}`)).not.toBe("");
        await expect(fixture.service.accessToken(saved.id)).rejects.toThrow("尚未授权");
        const retry = await fixture.start(saved.id);
        expect(retry.authorization.searchParams.get("prompt")).toBe("consent");
      } else {
        expect(fixture.store.vault.get(`chatgpt:${saved.id}`)).toBe("");
      }
      expect(saved.subject).toBe(mismatch === "permission" ? "account-subject" : "");
    },
  );

  it("handles access denial without exchanging a code and hides provider details", async () => {
    const fixture = setup();
    const { attempt, authorization } = await fixture.start();
    await fixture.callback(authorization, {
      error: "access_denied",
      error_description: "private server detail",
    });
    expect(fixture.service.loginStatus(attempt.id)).toMatchObject({
      status: "failed",
      error: expect.stringContaining("未获批准"),
    });
    expect(JSON.stringify(fixture.service.loginStatus(attempt.id))).not.toContain(
      "private server detail",
    );
    expect(fixture.requests).toHaveLength(0);
  });

  it("reuses the stable host and registration, validates returning subject and client", async () => {
    const fixture = setup();
    const account = await fixture.login();
    const hostId = fixture.store.vault.get("chatgpt-host:id");
    const { attempt, authorization } = await fixture.start(account.id);
    expect(authorization.searchParams.get("client_id")).toBe(clientId);
    expect(authorization.searchParams.get("ext_agent_host_id")).toBe(hostId);
    expect(authorization.searchParams.has("agent_name_hint")).toBe(false);
    expect(authorization.searchParams.get("id_token_hint")).toBeTruthy();
    fixture.setSubject("another-account");
    await fixture.callback(authorization);
    expect(fixture.service.loginStatus(attempt.id).status).toBe("failed");
    expect(await fixture.service.accessToken(account.id)).toBe(accessToken);
    const next = await fixture.start(account.id);
    await fixture.callback(next.authorization, { client_id: "another-client" });
    expect(fixture.service.loginStatus(next.attempt.id).status).toBe("failed");
    expect(fixture.service.accounts()).toHaveLength(1);
  });

  it("serializes refreshes and atomically retains rotating refresh tokens and scopes", async () => {
    const fixture = setup();
    const account = await fixture.login();
    fixture.expire(account.id);
    const entered = deferred<void>();
    const response = deferred<Response>();
    fixture.setTokenHook(async (body) => {
      expect(body.get("grant_type")).toBe("refresh_token");
      expect(body.get("refresh_token")).toBe(refreshToken);
      expect(body.get("client_id")).toBe(clientId);
      expect(body.has("scope")).toBe(false);
      entered.resolve();
      return response.promise;
    });
    const first = fixture.service.accessToken(account.id);
    const second = fixture.service.accessToken(account.id);
    await entered.promise;
    response.resolve(
      json({
        access_token: "rotated-access",
        refresh_token: "rotated-refresh",
        token_type: "Bearer",
        expires_in: 3600,
      }),
    );
    expect(await Promise.all([first, second])).toEqual(["rotated-access", "rotated-access"]);
    expect(
      fixture.requests.filter((request) => request.body.get("grant_type") === "refresh_token"),
    ).toHaveLength(1);
    expect(JSON.parse(fixture.store.vault.get(`chatgpt:${account.id}`))).toMatchObject({
      accessToken: "rotated-access",
      refreshToken: "rotated-refresh",
      scopes: grantedScope.split(" "),
    });
  });

  it("does not restore credentials when sign-out races a refresh, and retains registration", async () => {
    const fixture = setup();
    const account = await fixture.login();
    fixture.expire(account.id);
    const entered = deferred<void>();
    const response = deferred<Response>();
    fixture.setTokenHook(async () => {
      entered.resolve();
      return response.promise;
    });
    const refreshing = fixture.service.accessToken(account.id);
    const rejected = expect(refreshing).rejects.toThrow("状态已变更");
    await entered.promise;
    const signingOut = fixture.service.signOut(account.id);
    await expect(fixture.service.accessToken(account.id)).rejects.toThrow("已退出");
    response.resolve(
      json({
        access_token: "late-access",
        refresh_token: "late-refresh",
        token_type: "Bearer",
        expires_in: 3600,
      }),
    );
    await rejected;
    expect(await signingOut).toEqual({ message: "已退出 ChatGPT 账号" });
    expect(fixture.store.vault.get(`chatgpt:${account.id}`)).toBe("");
    expect(fixture.service.accounts()).toEqual([{ ...account, connected: false }]);
    expect(
      fixture.requests.find((request) => request.url.endsWith("/oauth/revoke"))?.body.get("token"),
    ).toBe("late-refresh");
  });

  it("cancels pending exchanges without saving a late result", async () => {
    const fixture = setup();
    const entered = deferred<void>();
    const response = deferred<Response>();
    fixture.setTokenHook(async () => {
      entered.resolve();
      return response.promise;
    });
    const { attempt, authorization } = await fixture.start();
    const callback = fixture.callback(authorization);
    await entered.promise;
    expect(fixture.service.cancelLogin(attempt.id).status).toBe("cancelled");
    response.resolve(
      json({
        access_token: "late-access",
        refresh_token: "late-refresh",
        id_token: "invalid-late-id",
        token_type: "Bearer",
        expires_in: 3600,
        scope: grantedScope,
      }),
    );
    await callback;
    expect(fixture.service.loginStatus(attempt.id).status).toBe("cancelled");
    expect(fixture.service.accounts()).toEqual([expect.objectContaining({ connected: false })]);
    const saved = fixture.store.chatgptAccounts.list()[0]!;
    expect(fixture.store.vault.get(`chatgpt:${saved.id}`)).toBe("");
  });

  it("expires callbacks, closes cancelled listeners, and disposes outstanding attempts", async () => {
    const fixture = setup({ loginTimeoutMs: 30 });
    const { attempt, authorization } = await fixture.start();
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(fixture.service.loginStatus(attempt.id)).toMatchObject({
      status: "failed",
      error: expect.stringContaining("超时"),
    });
    await expect(fixture.callback(authorization)).rejects.toThrow();
    const pending = await fixture.start();
    fixture.service.onModuleDestroy();
    await expect(fixture.callback(pending.authorization)).rejects.toThrow();
    await expect(fixture.service.startLogin()).rejects.toThrow("已关闭");
  });

  it("lists only displayable account models and redacts network and provider failures", async () => {
    const fixture = setup();
    const account = await fixture.login();
    expect(await fixture.service.models(account.id)).toEqual([
      { id: "review-model", name: "Review model" },
    ]);
    const modelCall = fixture.fetcher.mock.calls.find(([input]) =>
      String(input).endsWith("/v1/models"),
    )!;
    expect(modelCall[1]?.headers).toEqual({ Authorization: `Bearer ${accessToken}` });
    fixture.setModels(json({ message: `private provider output ${accessToken}` }, 403));
    await expect(fixture.service.models(account.id)).rejects.toThrow("权限不足");
    fixture.expire(account.id);
    fixture.setTokenHook(async () => {
      throw new Error(`private network error ${refreshToken}`);
    });
    try {
      await fixture.service.accessToken(account.id);
      throw new Error("expected rejection");
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      const visible = `${String(error)} ${JSON.stringify(error)}`;
      expect(visible).not.toContain(refreshToken);
      expect(visible).not.toContain("private network error");
    }
  });

  it("retains an issued client after failed exchange and retries using that registration", async () => {
    const fixture = setup();
    fixture.setTokenHook(async () =>
      json({ error: "invalid_grant", error_description: "private provider detail" }, 400),
    );
    const { attempt, authorization } = await fixture.start();
    await fixture.callback(authorization);
    const failed = fixture.service.loginStatus(attempt.id);
    expect(failed).toMatchObject({ status: "failed", account: { connected: false } });
    const registration = fixture.store.chatgptAccounts.list()[0]!;
    expect(registration).toMatchObject({ clientId, subject: "", email: "" });
    const retry = await fixture.start(registration.id);
    expect(retry.authorization.searchParams.get("client_id")).toBe(clientId);
    expect(retry.authorization.searchParams.has("agent_name_hint")).toBe(false);
    expect(retry.authorization.searchParams.has("login_hint")).toBe(false);
    expect(JSON.stringify(failed)).not.toContain("private provider detail");
  });

  it("cancels an older reauthorization for the same account", async () => {
    const fixture = setup();
    const account = await fixture.login();
    const first = await fixture.start(account.id);
    const second = await fixture.start(account.id);
    expect(fixture.service.loginStatus(first.attempt.id).status).toBe("cancelled");
    await expect(fixture.callback(first.authorization)).rejects.toThrow();
    await fixture.callback(second.authorization);
    expect(fixture.service.loginStatus(second.attempt.id).status).toBe("completed");
    expect(fixture.service.accounts()).toEqual([account]);
  });

  it("clears a definitively expired grant while keeping its issued registration", async () => {
    const fixture = setup();
    const account = await fixture.login();
    fixture.expire(account.id);
    fixture.setTokenHook(async () =>
      json({ error: "invalid_grant", error_description: refreshToken }, 400),
    );
    await expect(fixture.service.accessToken(account.id)).rejects.toThrow("已失效");
    expect(fixture.store.vault.get(`chatgpt:${account.id}`)).toBe("");
    expect(fixture.store.chatgptAccounts.get(account.id)?.clientId).toBe(clientId);
    expect(fixture.service.accounts()).toEqual([{ ...account, connected: false }]);
  });

  it("preserves tokens on invalid_client and does not force consent on routine reauthorization", async () => {
    const fixture = setup();
    const account = await fixture.login();
    const reauthorization = await fixture.start(account.id);
    expect(reauthorization.authorization.searchParams.has("prompt")).toBe(false);
    fixture.expire(account.id);
    const saved = fixture.store.vault.get(`chatgpt:${account.id}`);
    fixture.setTokenHook(async () => json({ error: "invalid_client" }, 401));
    await expect(fixture.service.accessToken(account.id)).rejects.toThrow("客户端配置");
    expect(fixture.store.vault.get(`chatgpt:${account.id}`)).toBe(saved);
    expect(fixture.service.accounts()[0]?.connected).toBe(true);
  });

  it("finishes local sign-out before shutdown closes the store during remote revocation", async () => {
    const fixture = setup();
    const account = await fixture.login();
    const entered = deferred<void>();
    const response = deferred<Response>();
    fixture.fetcher.mockImplementation(async () => {
      entered.resolve();
      return response.promise;
    });
    const signingOut = fixture.service.signOut(account.id);
    await entered.promise;
    fixture.service.onModuleDestroy();
    expect(fixture.store.vault.get(`chatgpt:${account.id}`)).toBe("");
    fixture.store.close();
    fixtures.splice(
      fixtures.findIndex((item) => item.service === fixture.service),
      1,
    );
    response.resolve(new Response(null, { status: 200 }));
    await expect(signingOut).resolves.toEqual({ message: "已退出 ChatGPT 账号" });
    // Repeated framework cleanup remains safe after the Store has closed.
    fixture.service.onModuleDestroy();
  });

  it("clears local credentials and warns if remote revocation cannot be confirmed", async () => {
    const fixture = setup();
    const account = await fixture.login();
    fixture.fetcher.mockImplementation(async () => {
      throw new Error("private revocation failure");
    });
    expect((await fixture.service.signOut(account.id)).message).toContain("未能确认远端授权撤销");
    expect(fixture.store.vault.get(`chatgpt:${account.id}`)).toBe("");
    expect(fixture.service.accounts()).toEqual([{ ...account, connected: false }]);
    const next = await fixture.start(account.id);
    expect(next.authorization.searchParams.has("id_token_hint")).toBe(false);
    expect(next.authorization.searchParams.get("client_id")).toBe(clientId);
  });
});
