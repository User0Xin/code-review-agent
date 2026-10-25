import type { ChatGptAccount, ChatGptLoginAttempt, ChatGptModel } from "@review/contracts";
import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createRemoteJWKSet, customFetch, jwtVerify, type JWTVerifyGetKey } from "jose";
import { z } from "zod";
import { DomainError } from "../common/domain-error.js";
import type { Store } from "../store.js";

const issuer = "https://auth.openai.com";
const tokenEndpoint = `${issuer}/api/accounts/oauth/token`;
const resource = "https://api.openai.com/v1";
const planScope = "chatgpt.tokens.use.direct";
const unusableRefreshErrors = new Set([
  "invalid_grant",
  "invalid_refresh_token",
  "token_expired",
  "refresh_token_expired",
  "refresh_token_invalidated",
  "refresh_token_reused",
]);
const scope = `openid profile email offline_access resource.invoke ${planScope}`;
const credentialsSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  idToken: z.string().min(1),
  expiresAt: z.number().finite(),
  scopes: z.array(z.string()),
});
type Credentials = z.infer<typeof credentialsSchema>;
const tokenSchema = z.object({
  access_token: z.string().min(1),
  refresh_token: z.string().min(1).optional(),
  id_token: z.string().min(1).optional(),
  token_type: z.string().refine((value) => value.toLowerCase() === "bearer"),
  expires_in: z.number().positive().finite(),
  scope: z.string().optional(),
});
type TokenResponse = z.infer<typeof tokenSchema>;

class RejectedGrantError extends DomainError {
  constructor(message: string) {
    super(message);
  }
}

interface PendingLogin {
  public: ChatGptLoginAttempt;
  accountId: string;
  selectedAccount: ReturnType<Store["chatgptAccounts"]["get"]>;
  state: string;
  nonce: string;
  verifier: string;
  redirectUri: string;
  clientId: string;
  server: Server;
  timer: ReturnType<typeof setTimeout>;
  controller: AbortController;
  processing: boolean;
}

export interface ChatGptAuthOptions {
  fetch?: typeof fetch;
  /** Inject a local JWKS resolver in tests; production always uses OpenAI's published JWKS. */
  verificationKey?: JWTVerifyGetKey;
  requestTimeoutMs?: number;
  loginTimeoutMs?: number;
}

/** Keeps OAuth credentials in the backend vault; HTTP responses contain account metadata only. */
export class ChatGptAuthService {
  private readonly fetcher: typeof fetch;
  private readonly verificationKey: JWTVerifyGetKey;
  private readonly requestTimeoutMs: number;
  private readonly loginTimeoutMs: number;
  private readonly attempts = new Map<string, PendingLogin>();
  private readonly refreshes = new Map<string, Promise<string>>();
  private readonly revocationTokens = new Map<string, string>();
  private readonly generations = new Map<string, number>();
  private readonly signingOut = new Set<string>();
  private destroyed = false;

  constructor(
    private readonly store: Store,
    options: ChatGptAuthOptions = {},
  ) {
    this.fetcher = options.fetch ?? fetch;
    this.requestTimeoutMs = options.requestTimeoutMs ?? 15_000;
    this.loginTimeoutMs = options.loginTimeoutMs ?? 10 * 60_000;
    this.verificationKey =
      options.verificationKey ??
      createRemoteJWKSet(new URL(`${issuer}/.well-known/jwks.json`), {
        [customFetch]: (input, init) => this.fetcher(input, { ...init, redirect: "error" }),
        timeoutDuration: this.requestTimeoutMs,
      });
  }

  accounts(): ChatGptAccount[] {
    return this.store.chatgptAccounts.list().map((account) => ({
      id: account.id,
      email: account.email || `待完成登录 (${account.id.slice(0, 8)})`,
      connected:
        !this.signingOut.has(account.id) &&
        Boolean(this.credentials(account.id)?.scopes.includes(planScope)),
    }));
  }

  async startLogin(accountId?: string): Promise<ChatGptLoginAttempt> {
    if (this.destroyed) {
      throw new DomainError("ChatGPT 登录服务已关闭");
    }
    const selectedAccount = accountId ? this.store.chatgptAccounts.get(accountId) : undefined;
    if (accountId && !selectedAccount) {
      throw new DomainError("ChatGPT 账号不存在", "not-found");
    }
    if (accountId && this.signingOut.has(accountId)) {
      throw new DomainError("账号正在退出，请稍后重试");
    }
    const hostId = this.hostId();
    const savedCredentials = selectedAccount ? this.credentials(selectedAccount.id) : undefined;
    const id = randomUUID();
    const state = randomBytes(32).toString("base64url");
    const nonce = randomBytes(32).toString("base64url");
    const verifier = randomBytes(64).toString("base64url");
    const server = createServer((request, response) => {
      void this.callback(id, request, response).catch(() => {
        this.respond(response, 500, "登录未完成，请返回应用重新登录。");
      });
    });
    try {
      await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => {
          server.removeListener("error", reject);
          resolve();
        });
      });
    } catch {
      server.close();
      throw new DomainError("无法启动本机登录回调，请检查网络权限后重试");
    }
    const address = server.address();
    if (this.destroyed || !address || typeof address === "string") {
      server.close();
      throw new DomainError("ChatGPT 登录服务不可用");
    }
    const redirectUri = `http://127.0.0.1:${address.port}/auth/callback`;
    const clientId = selectedAccount?.clientId ?? "dynamic_agent_client";
    const url = new URL(`${issuer}/api/accounts/authorize`);
    const parameters: Record<string, string> = {
      client_id: clientId,
      ext_agent_host_id: hostId,
      response_type: "code",
      redirect_uri: redirectUri,
      scope,
      resource,
      state,
      nonce,
      code_challenge_method: "S256",
      code_challenge: createHash("sha256").update(verifier).digest("base64url"),
    };
    if (selectedAccount) {
      if (selectedAccount.email) {
        parameters.login_hint = selectedAccount.email;
      }
      if (savedCredentials) {
        parameters.id_token_hint = savedCredentials.idToken;
      }
      if (this.store.vault.get(`chatgpt-consent:${selectedAccount.id}`)) {
        parameters.prompt = "consent";
      }
    } else {
      parameters.agent_name_hint = "SparkShield";
    }
    for (const [key, value] of Object.entries(parameters)) {
      url.searchParams.set(key, value);
    }
    const attempt: PendingLogin = {
      public: {
        id,
        status: "pending",
        authorizationUrl: url.toString(),
        expiresAt: new Date(Date.now() + this.loginTimeoutMs).toISOString(),
      },
      accountId: selectedAccount?.id ?? randomUUID(),
      selectedAccount,
      state,
      nonce,
      verifier,
      redirectUri,
      clientId,
      server,
      timer: setTimeout(
        () => this.finish(id, "failed", "登录已超时，请重新登录"),
        this.loginTimeoutMs,
      ),
      controller: new AbortController(),
      processing: false,
    };
    attempt.timer.unref();
    server.unref();
    for (const [previousId, previous] of this.attempts) {
      if (previous.accountId === attempt.accountId && previous.public.status === "pending") {
        this.finish(previousId, "cancelled");
      }
    }
    this.attempts.set(id, attempt);
    return { ...attempt.public };
  }

  loginStatus(id: string): ChatGptLoginAttempt {
    const attempt = this.attempts.get(id);
    if (!attempt) {
      throw new DomainError("登录请求不存在或已过期", "not-found");
    }
    // The authorization URL may contain an ID-token hint. Return it only when starting login.
    return { ...attempt.public, authorizationUrl: undefined };
  }

  cancelLogin(id: string): ChatGptLoginAttempt {
    const attempt = this.attempts.get(id);
    if (!attempt) {
      throw new DomainError("登录请求不存在或已过期", "not-found");
    }
    if (attempt.public.status === "pending") {
      this.finish(id, "cancelled");
    }
    return this.loginStatus(id);
  }

  async accessToken(accountId: string): Promise<string> {
    if (this.destroyed || this.signingOut.has(accountId)) {
      throw new DomainError("ChatGPT 账号已退出，请重新登录");
    }
    const credentials = this.credentials(accountId);
    if (!credentials) {
      throw new DomainError("ChatGPT 账号未登录或授权已失效，请重新登录");
    }
    this.requirePlan(credentials.scopes);
    if (credentials.expiresAt > Date.now() + 60_000) {
      return credentials.accessToken;
    }
    const existing = this.refreshes.get(accountId);
    if (existing) {
      return existing;
    }
    const promise = this.refresh(accountId, credentials).finally(() => {
      if (this.refreshes.get(accountId) === promise) {
        this.refreshes.delete(accountId);
      }
    });
    this.refreshes.set(accountId, promise);
    return promise;
  }

  async models(accountId: string): Promise<ChatGptModel[]> {
    const accessToken = await this.accessToken(accountId);
    try {
      const response = await this.fetcher(`${resource}/models`, {
        headers: { Authorization: `Bearer ${accessToken}` },
        redirect: "error",
        signal: AbortSignal.timeout(this.requestTimeoutMs),
      });
      if (!response.ok) {
        throw this.httpError(response.status, "获取模型");
      }
      const catalog = z
        .object({
          models: z.array(
            z.object({
              slug: z.string().min(1),
              display_name: z.string().min(1),
              visibility: z.string(),
            }),
          ),
        })
        .parse(await response.json());
      return catalog.models
        .filter((model) => model.visibility === "list")
        .map((model) => ({ id: model.slug, name: model.display_name }));
    } catch (error) {
      throw this.safeError(error, "获取 ChatGPT 模型失败，请检查网络后重试");
    }
  }

  async signOut(accountId: string): Promise<{ message: string }> {
    const account = this.store.chatgptAccounts.get(accountId);
    if (!account) {
      throw new DomainError("ChatGPT 账号不存在", "not-found");
    }
    if (this.signingOut.has(accountId)) {
      throw new DomainError("账号正在退出，请稍后重试");
    }
    const credentials = this.credentials(accountId);
    this.signingOut.add(accountId);
    this.generations.set(accountId, this.generation(accountId) + 1);
    for (const [id, attempt] of this.attempts) {
      if (attempt.accountId === accountId && attempt.public.status === "pending") {
        this.finish(id, "cancelled");
      }
    }
    let confirmed = !credentials;
    try {
      if (credentials) {
        // A concurrent refresh can rotate the token. Wait for its bounded request and revoke
        // the replacement without making those credentials available to callers again.
        const refreshing = this.refreshes.get(accountId);
        if (refreshing) {
          await refreshing.catch(() => undefined);
        }
        const refreshToken = this.revocationTokens.get(accountId) ?? credentials.refreshToken;
        for (let retry = 0; retry < 2; retry++) {
          try {
            const response = await this.fetcher(`${issuer}/api/accounts/oauth/revoke`, {
              method: "POST",
              redirect: "error",
              headers: { "Content-Type": "application/x-www-form-urlencoded" },
              body: new URLSearchParams({
                token: refreshToken,
                token_type_hint: "refresh_token",
                client_id: account.clientId,
              }),
              signal: AbortSignal.timeout(this.requestTimeoutMs),
            });
            confirmed = response.status === 200;
            if (confirmed || response.status < 500) {
              break;
            }
          } catch {
            // Keep the refresh token available until the bounded revocation retry ends.
          }
          if (retry === 0) {
            await new Promise((resolve) => setTimeout(resolve, 100));
          }
        }
      }
    } finally {
      if (!this.destroyed) {
        this.store.vault.set(`chatgpt:${accountId}`, "");
      }
      this.signingOut.delete(accountId);
      this.revocationTokens.delete(accountId);
    }
    return {
      message: confirmed
        ? "已退出 ChatGPT 账号"
        : "已在本机退出，未能确认远端授权撤销；可在 ChatGPT 设置中断开此应用",
    };
  }

  onModuleDestroy(): void {
    if (this.destroyed) {
      return;
    }
    this.destroyed = true;
    // Store closes immediately after module teardown; finish local sign-out synchronously.
    for (const accountId of this.signingOut) {
      this.store.vault.set(`chatgpt:${accountId}`, "");
    }
    for (const [id, attempt] of this.attempts) {
      if (attempt.public.status === "pending") {
        this.finish(id, "cancelled");
      }
      clearTimeout(attempt.timer);
    }
    this.attempts.clear();
  }

  private async callback(
    id: string,
    request: IncomingMessage,
    response: ServerResponse,
  ): Promise<void> {
    const attempt = this.attempts.get(id);
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (request.method !== "GET" || url.pathname !== "/auth/callback") {
      this.respond(response, 404, "未找到登录回调。");
      return;
    }
    if (!attempt || attempt.public.status !== "pending" || attempt.processing) {
      this.respond(response, 409, "登录请求已结束，请返回应用。");
      return;
    }
    const state = url.searchParams.get("state") ?? "";
    if (!this.same(state, attempt.state)) {
      this.respond(response, 400, "登录验证失败，请使用原始登录页面重试。");
      return;
    }
    attempt.processing = true;
    try {
      const clientId = url.searchParams.get("client_id");
      if (attempt.selectedAccount) {
        if (clientId && clientId !== attempt.clientId) {
          throw new DomainError("登录账号注册信息不匹配，请重新登录");
        }
      } else if (clientId && clientId !== "dynamic_agent_client") {
        attempt.clientId = clientId;
        // Retain an issued registration before exchange, even if consent or code exchange fails.
        const existing = this.store.chatgptAccounts
          .list()
          .find((account) => account.clientId === clientId);
        if (existing) {
          attempt.selectedAccount = existing;
          attempt.accountId = existing.id;
        } else {
          const now = new Date().toISOString();
          attempt.selectedAccount = this.store.chatgptAccounts.save({
            id: attempt.accountId,
            clientId,
            subject: "",
            email: "",
            createdAt: now,
            updatedAt: now,
          });
        }
        attempt.public.account = this.accounts().find(
          (account) => account.id === attempt.accountId,
        );
      }
      if (this.signingOut.has(attempt.accountId)) {
        throw new DomainError("账号正在退出，请稍后重试");
      }
      if (url.searchParams.get("error") === "access_denied" && attempt.selectedAccount) {
        this.store.vault.set(`chatgpt-consent:${attempt.accountId}`, "required");
      }
      if (url.searchParams.has("error")) {
        throw new DomainError(
          url.searchParams.get("error") === "access_denied"
            ? "ChatGPT 授权未获批准，请重新登录并允许使用 ChatGPT 额度"
            : "ChatGPT 登录未完成，请重新登录",
        );
      }
      if (attempt.clientId === "dynamic_agent_client") {
        throw new DomainError("ChatGPT 注册未完成，请重新登录");
      }
      const code = url.searchParams.get("code");
      if (!code) {
        throw new DomainError("ChatGPT 登录未返回授权码，请重新登录");
      }
      const tokens = await this.exchange(
        {
          grant_type: "authorization_code",
          client_id: attempt.clientId,
          code,
          code_verifier: attempt.verifier,
          redirect_uri: attempt.redirectUri,
          resource,
        },
        attempt.controller.signal,
      );
      if (!tokens.id_token || !tokens.refresh_token) {
        throw new DomainError("ChatGPT 登录凭据不完整，请重新登录");
      }
      const identity = await this.identity(tokens.id_token, attempt.clientId, attempt.nonce);
      if (
        attempt.selectedAccount?.subject &&
        identity.subject !== attempt.selectedAccount.subject
      ) {
        throw new DomainError("登录身份与所选 ChatGPT 账号不匹配，请重新登录");
      }
      const scopes = this.scopes(tokens.scope);
      if (
        this.destroyed ||
        attempt.public.status !== "pending" ||
        attempt.controller.signal.aborted ||
        this.signingOut.has(attempt.accountId)
      ) {
        this.respond(response, 409, "登录请求已结束，请返回应用。");
        return;
      }
      const now = new Date().toISOString();
      const registration = {
        id: attempt.accountId,
        clientId: attempt.clientId,
        subject: identity.subject,
        email: identity.email,
        createdAt: attempt.selectedAccount?.createdAt ?? now,
        updatedAt: now,
      };
      const idToken = tokens.id_token;
      const refreshToken = tokens.refresh_token;
      this.store.transaction(() => {
        this.store.chatgptAccounts.save(registration);
        this.store.vault.set(
          `chatgpt-consent:${registration.id}`,
          scopes.includes(planScope) ? "" : "required",
        );
        this.store.vault.set(
          `chatgpt:${registration.id}`,
          JSON.stringify({
            accessToken: tokens.access_token,
            refreshToken,
            idToken,
            expiresAt: Date.now() + tokens.expires_in * 1000,
            scopes,
          } satisfies Credentials),
        );
      });
      this.generations.set(registration.id, this.generation(registration.id) + 1);
      attempt.public.account = {
        id: registration.id,
        email: registration.email,
        connected: scopes.includes(planScope),
      };
      this.requirePlan(scopes);
      this.finish(id, "completed");
      this.respond(response, 200, "ChatGPT 登录成功，可以关闭此页面并返回应用。");
    } catch (error) {
      const safe = this.safeError(error, "ChatGPT 登录失败，请重新登录或检查网络");
      if (attempt.public.status === "pending") {
        this.finish(id, "failed", safe.message);
      }
      this.respond(response, 400, "ChatGPT 登录未完成，请返回应用查看详情。");
    }
  }

  private async refresh(accountId: string, previous: Credentials): Promise<string> {
    const account = this.store.chatgptAccounts.get(accountId);
    if (!account) {
      throw new DomainError("ChatGPT 账号不存在", "not-found");
    }
    const generation = this.generation(accountId);
    let replacementRefreshToken: string | undefined;
    try {
      const tokens = await this.exchange({
        grant_type: "refresh_token",
        client_id: account.clientId,
        refresh_token: previous.refreshToken,
        resource,
      });
      replacementRefreshToken = tokens.refresh_token;
      if (tokens.id_token) {
        const identity = await this.identity(tokens.id_token, account.clientId);
        if (identity.subject !== account.subject) {
          throw new DomainError("ChatGPT 刷新身份不匹配，请重新登录");
        }
      }
      const scopes = tokens.scope === undefined ? previous.scopes : this.scopes(tokens.scope);
      if (
        this.destroyed ||
        this.signingOut.has(accountId) ||
        this.generation(accountId) !== generation
      ) {
        throw new DomainError("ChatGPT 登录状态已变更，请重试");
      }
      const credentials: Credentials = {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token ?? previous.refreshToken,
        idToken: tokens.id_token ?? previous.idToken,
        expiresAt: Date.now() + tokens.expires_in * 1000,
        scopes,
      };
      this.store.transaction(() => {
        this.store.vault.set(`chatgpt:${accountId}`, JSON.stringify(credentials));
        this.store.vault.set(
          `chatgpt-consent:${accountId}`,
          scopes.includes(planScope) ? "" : "required",
        );
      });
      this.requirePlan(scopes);
      return credentials.accessToken;
    } catch (error) {
      if (
        !this.destroyed &&
        error instanceof RejectedGrantError &&
        this.generation(accountId) === generation
      ) {
        this.store.vault.set(`chatgpt:${accountId}`, "");
      }
      throw error;
    } finally {
      if (
        replacementRefreshToken &&
        this.signingOut.has(accountId) &&
        this.generation(accountId) === generation + 1
      ) {
        this.revocationTokens.set(accountId, replacementRefreshToken);
      }
    }
  }

  private async exchange(
    parameters: Record<string, string>,
    signal?: AbortSignal,
  ): Promise<TokenResponse> {
    try {
      const response = await this.fetcher(tokenEndpoint, {
        method: "POST",
        redirect: "error",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(parameters),
        signal: signal
          ? AbortSignal.any([signal, AbortSignal.timeout(this.requestTimeoutMs)])
          : AbortSignal.timeout(this.requestTimeoutMs),
      });
      if (!response.ok) {
        const failure = z
          .object({ error: z.union([z.string(), z.object({ code: z.string() })]) })
          .safeParse(await response.json().catch(() => undefined));
        const code = failure.success
          ? typeof failure.data.error === "string"
            ? failure.data.error
            : failure.data.error.code
          : undefined;
        if (parameters.grant_type === "refresh_token" && code && unusableRefreshErrors.has(code)) {
          throw new RejectedGrantError("ChatGPT 授权已失效，请重新登录");
        }
        throw this.httpError(response.status, "授权");
      }
      return tokenSchema.parse(await response.json());
    } catch (error) {
      throw this.safeError(error, "ChatGPT 授权请求失败，请重新登录或检查网络");
    }
  }

  private async identity(
    token: string,
    clientId: string,
    nonce?: string,
  ): Promise<{ subject: string; email: string }> {
    try {
      const { payload } = await jwtVerify(token, this.verificationKey, {
        algorithms: ["RS256"],
        issuer,
        audience: clientId,
        requiredClaims: nonce ? ["sub", "exp", "nonce"] : ["sub", "exp"],
      });
      if (
        !payload.sub ||
        typeof payload.email !== "string" ||
        !payload.email ||
        (nonce !== undefined &&
          (typeof payload.nonce !== "string" || !this.same(payload.nonce, nonce)))
      ) {
        throw new Error("invalid identity");
      }
      return { subject: payload.sub, email: payload.email };
    } catch {
      throw new DomainError("ChatGPT 身份验证失败，请重新登录");
    }
  }

  private credentials(accountId: string): Credentials | undefined {
    const value = this.store.vault.get(`chatgpt:${accountId}`);
    if (!value) {
      return undefined;
    }
    try {
      return credentialsSchema.parse(JSON.parse(value));
    } catch {
      throw new DomainError("ChatGPT 已保存凭据无效，请重新登录");
    }
  }

  private hostId(): string {
    let hostId = this.store.vault.get("chatgpt-host:id");
    if (!hostId) {
      hostId = `urn:uuid:${randomUUID()}`;
      this.store.vault.set("chatgpt-host:id", hostId);
    }
    return hostId;
  }

  private finish(id: string, status: "completed" | "failed" | "cancelled", error?: string): void {
    const attempt = this.attempts.get(id);
    if (!attempt || attempt.public.status !== "pending") {
      return;
    }
    clearTimeout(attempt.timer);
    attempt.public.status = status;
    attempt.public.authorizationUrl = undefined;
    if (error) {
      attempt.public.error = error;
    }
    attempt.controller.abort();
    attempt.server.close();
    attempt.state = "";
    attempt.nonce = "";
    attempt.verifier = "";
    // Briefly retain the public terminal result so the frontend can poll it.
    attempt.timer = setTimeout(() => this.attempts.delete(id), 10 * 60_000);
    attempt.timer.unref();
  }

  private generation(accountId: string): number {
    return this.generations.get(accountId) ?? 0;
  }

  private scopes(value?: string): string[] {
    return value?.split(/\s+/).filter(Boolean) ?? [];
  }

  private requirePlan(scopes: string[]): void {
    if (!scopes.includes(planScope)) {
      throw new DomainError("尚未授权使用 ChatGPT 额度，请重新登录并授予所需权限");
    }
  }

  private same(actual: string, expected: string): boolean {
    const a = Buffer.from(actual);
    const b = Buffer.from(expected);
    return a.length === b.length && timingSafeEqual(a, b);
  }

  private httpError(status: number, operation: string): DomainError {
    if (status === 401 || status === 400) {
      return new DomainError(`ChatGPT ${operation}未获接受，请重新登录或检查客户端配置`);
    }
    if (status === 403) {
      return new DomainError("ChatGPT 权限不足，请检查账号授权与额度设置");
    }
    if (status === 429) {
      return new DomainError("ChatGPT 请求受限或额度不足，请稍后重试");
    }
    return new DomainError(`ChatGPT ${operation}失败（HTTP ${status}），请稍后重试`);
  }

  private safeError(error: unknown, fallback: string): DomainError {
    return error instanceof DomainError ? error : new DomainError(fallback);
  }

  private respond(response: ServerResponse, status: number, message: string): void {
    if (response.writableEnded || response.destroyed) {
      return;
    }
    response.writeHead(status, {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
    });
    response.end(message);
  }
}
