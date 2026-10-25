import type { Project } from "@review/contracts";
import { IntegrationError } from "./integration-error.js";
import { readArray, readNumber, readObject, type PlatformObject } from "./platform-values.js";

const networkCodes = new Set([
  "ENOTFOUND",
  "EAI_AGAIN",
  "ECONNREFUSED",
  "ECONNRESET",
  "ENETUNREACH",
  "EHOSTUNREACH",
  "EPIPE",
  "ETIMEDOUT",
  "UND_ERR_CONNECT_TIMEOUT",
  "UND_ERR_HEADERS_TIMEOUT",
  "UND_ERR_BODY_TIMEOUT",
  "UND_ERR_SOCKET",
  "CERT_HAS_EXPIRED",
  "DEPTH_ZERO_SELF_SIGNED_CERT",
  "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
  "SELF_SIGNED_CERT_IN_CHAIN",
  "ERR_TLS_CERT_ALTNAME_INVALID",
]);

function networkCode(error: unknown): string | undefined {
  let current = error;
  const seen = new Set<object>();
  while (typeof current === "object" && current !== null && !seen.has(current) && seen.size < 8) {
    seen.add(current);
    if ("code" in current && typeof current.code === "string" && networkCodes.has(current.code)) {
      return current.code;
    }
    current = "cause" in current ? current.cause : undefined;
  }
  return undefined;
}

export interface RepositoryClientOptions {
  fetch?: typeof fetch;
  timeoutMs?: number;
  pageSize?: number;
  maxPages?: number;
  maxResponseBytes?: number;
}

export class RepositoryClient {
  private readonly fetcher: typeof fetch;
  private readonly timeoutMs: number;
  private readonly pageSize: number;
  private readonly maxPages: number;
  private readonly maxResponseBytes: number;

  constructor(options: RepositoryClientOptions = {}) {
    this.fetcher = options.fetch ?? globalThis.fetch;
    this.timeoutMs = options.timeoutMs ?? 15_000;
    this.pageSize = Math.max(1, Math.min(100, options.pageSize ?? 100));
    this.maxPages = Math.max(1, options.maxPages ?? 20);
    this.maxResponseBytes = options.maxResponseBytes ?? 8_000_000;
  }

  get itemLimit(): number {
    return this.pageSize * this.maxPages;
  }

  base(project: Project): string {
    const host = new URL(project.host);
    if (
      !["http:", "https:"].includes(host.protocol) ||
      host.username ||
      host.password ||
      host.search ||
      host.hash
    ) {
      throw new IntegrationError("仓库 host 必须是没有凭据的 HTTP(S) 地址");
    }
    const origin = host.origin;
    const path = host.pathname.replace(/\/$/, "");
    if (project.provider === "github") {
      const api =
        host.hostname === "github.com"
          ? "https://api.github.com"
          : host.hostname === "api.github.com"
            ? origin
            : `${origin}${path.endsWith("/api/v3") ? path : `${path}/api/v3`}`;
      if (!/^[^/]+\/[^/]+$/.test(project.repository)) {
        throw new IntegrationError("GitHub 仓库需要 owner/repo");
      }
      return `${api}/repos/${project.repository.split("/").map(encodeURIComponent).join("/")}`;
    }
    return `${origin}${path.endsWith("/api/v4") ? path : `${path}/api/v4`}/projects/${encodeURIComponent(project.repository)}`;
  }

  async request(
    project: Project,
    token: string,
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<{ value: unknown; headers: Headers }> {
    const signal = AbortSignal.timeout(this.timeoutMs);
    try {
      const response = await this.fetcher(`${this.base(project)}${path}`, {
        method,
        redirect: "error",
        signal,
        headers: {
          Accept: "application/json",
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          ...(project.provider === "github" ? { "X-GitHub-Api-Version": "2022-11-28" } : {}),
          ...(token.trim()
            ? project.provider === "github"
              ? { Authorization: `Bearer ${token}` }
              : { "PRIVATE-TOKEN": token }
            : {}),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new IntegrationError(
          `代码平台请求失败（HTTP ${response.status}）`,
          response.status === 408 || response.status === 429 || response.status >= 500,
          response.status,
        );
      }
      if (readNumber(Number(response.headers.get("content-length"))) > this.maxResponseBytes) {
        await response.body?.cancel();
        throw new IntegrationError("平台响应超过读取上限，请缩小变更范围");
      }
      const chunks: Uint8Array[] = [];
      let size = 0;
      const reader = response.body?.getReader();
      if (reader) {
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) {
              break;
            }
            size += value.byteLength;
            if (size > this.maxResponseBytes) {
              await reader.cancel();
              throw new IntegrationError("平台响应超过读取上限，请缩小变更范围");
            }
            chunks.push(value);
          }
        } finally {
          reader.releaseLock();
        }
      }
      const text = Buffer.concat(chunks).toString("utf8");
      return {
        value: text ? (JSON.parse(text) as unknown) : null,
        headers: response.headers,
      };
    } catch (error) {
      if (error instanceof IntegrationError) {
        throw error;
      }
      if (signal.aborted) {
        const limit =
          this.timeoutMs >= 1000 ? `${this.timeoutMs / 1000} 秒` : `${this.timeoutMs} 毫秒`;
        throw new IntegrationError(
          `代码平台请求超时（配置上限 ${limit}）：请检查网络、代理或平台状态后重试。`,
          true,
        );
      }
      if (error instanceof SyntaxError) {
        throw new IntegrationError(
          "代码平台返回 JSON 无效：请检查平台 API 或代理响应后重试。",
          true,
        );
      }
      const code = networkCode(error);
      throw new IntegrationError(
        `代码平台网络请求失败${code ? `（${code}）` : ""}：请检查网络、代理及平台连接后重试。`,
        true,
      );
    }
  }

  /** Never silently return an incomplete comment history: publication reconciliation relies on it. */
  async pages(project: Project, token: string, path: string): Promise<PlatformObject[]> {
    const result: PlatformObject[] = [];
    for (let page = 1; page <= this.maxPages; page++) {
      const { value, headers } = await this.request(
        project,
        token,
        `${path}${path.includes("?") ? "&" : "?"}per_page=${this.pageSize}&page=${page}`,
      );
      const batch = readArray(value).map(readObject);
      result.push(...batch);
      const next = headers.get("x-next-page");
      const links = headers.get("link");
      const hasMore =
        next !== null
          ? next !== ""
          : links !== null
            ? /rel="?next"?/.test(links)
            : batch.length >= this.pageSize;
      if (!hasMore) {
        return result;
      }
    }
    throw new IntegrationError("平台分页超过读取上限；未取得完整数据，已停止操作");
  }
}
