import {
  APICallError,
  JSONParseError,
  NoObjectGeneratedError,
  NoOutputGeneratedError,
  TypeValidationError,
} from "ai";
import { z } from "zod";
import { IntegrationError } from "./integration-error.js";

const schemaFields = new Set([
  "summary",
  "findings",
  "title",
  "body",
  "path",
  "line",
  "severity",
  "category",
  "evidence",
  "resolvedFindingIds",
  "verdict",
  "proposals",
  "kind",
  "ok",
]);
const expectedTypes = new Set([
  "string",
  "number",
  "int",
  "boolean",
  "array",
  "object",
  "null",
  "undefined",
  "nonoptional",
]);

function fieldPath(value: unknown): string {
  if (!Array.isArray(value) || value.length === 0) {
    return "根对象";
  }
  return value
    .slice(0, 10)
    .map((part: unknown, index) => {
      if (typeof part === "number" && Number.isSafeInteger(part) && part >= 0 && part <= 1000) {
        return `[${part}]`;
      }
      const field = typeof part === "string" && schemaFields.has(part) ? part : "未知字段";
      return index === 0 ? field : `.${field}`;
    })
    .join("");
}

function schemaDetails(chain: unknown[]): string {
  const issues: unknown[] = chain.flatMap((source) => {
    if (source instanceof z.ZodError) {
      return source.issues;
    }
    if (TypeValidationError.isInstance(source) && Array.isArray(source.cause)) {
      const issues: unknown[] = source.cause;
      return issues;
    }
    return [];
  });
  const details = new Set<string>();
  for (const issue of issues.slice(0, 10)) {
    const expected = property(issue, "expected");
    const type =
      typeof expected === "string" && expectedTypes.has(expected) ? expected : "约定类型";
    const path = fieldPath(property(issue, "path"));
    switch (property(issue, "code")) {
      case "invalid_type":
        details.add(`${path} 应为 ${type}`);
        break;
      case "invalid_value":
        details.add(`${path} 应为约定枚举值`);
        break;
      case "too_small":
      case "too_big":
        details.add(`${path} 的长度或数值超出约定范围`);
        break;
      default:
        details.add(`${path} 不符合字段约定`);
    }
    if (details.size === 3) {
      break;
    }
  }
  return [...details].join("；");
}

function outputError(chain: unknown[]): IntegrationError | undefined {
  const objectError = chain.find((source) => NoObjectGeneratedError.isInstance(source));
  if (objectError?.finishReason === "length") {
    return new IntegrationError(
      "模型输出被截断（finishReason=length）：已达到输出 token 上限，请缩小 PR 或减少本轮输出范围后重新审查。",
      true,
    );
  }
  if (objectError?.finishReason === "content-filter") {
    return new IntegrationError(
      "模型输出被内容过滤中止（finishReason=content-filter）：请检查输入内容或更换支持该审查场景的模型。",
      false,
    );
  }
  if (
    chain.some((source) => TypeValidationError.isInstance(source) || source instanceof z.ZodError)
  ) {
    const details = schemaDetails(chain);
    return new IntegrationError(
      `模型结构化输出无效（Schema 校验失败）：${details ? `${details}。` : "返回字段不符合约定。"}请使用支持 JSON Schema 的模型并重新审查。`,
      true,
    );
  }
  if (chain.some((source) => JSONParseError.isInstance(source))) {
    return new IntegrationError(
      "模型结构化输出无效（JSON 解析失败）：返回内容不是完整、有效的 JSON，请检查模型的结构化输出支持后重试。",
      true,
    );
  }
  if (chain.some((source) => NoOutputGeneratedError.isInstance(source))) {
    return new IntegrationError(
      "模型未生成输出：接口未返回可用结果，请重试或检查模型兼容性。",
      true,
    );
  }
  if (objectError) {
    return new IntegrationError(
      "模型未生成有效对象：请检查模型的 JSON Schema 结构化输出支持后重试。",
      true,
    );
  }
  return undefined;
}

function causeChain(error: unknown): unknown[] {
  const chain: unknown[] = [];
  const seen = new Set<object>();
  let current = error;
  while (
    typeof current === "object" &&
    current !== null &&
    !seen.has(current) &&
    chain.length < 12
  ) {
    seen.add(current);
    chain.push(current);
    current = "cause" in current ? current.cause : undefined;
  }
  return chain;
}

function property(error: unknown, key: string): unknown {
  return typeof error === "object" && error !== null && key in error
    ? Reflect.get(error, key)
    : undefined;
}

function timeoutError(timeoutMs: number, code?: string): IntegrationError {
  const limit =
    Number.isFinite(timeoutMs) && timeoutMs > 0 ? `配置上限 ${Math.ceil(timeoutMs / 1000)} 秒` : "";
  return new IntegrationError(
    `模型请求超时（${[limit, code].filter(Boolean).join("；")}）：请稍后重试，或缩小 PR、切换较快模型并检查网络。`,
    true,
  );
}

function networkError(chain: unknown[], timeoutMs: number): IntegrationError | undefined {
  for (const error of chain) {
    const name = property(error, "name");
    const code = property(error, "code");
    if (name === "TimeoutError") {
      return timeoutError(timeoutMs);
    }
    if (name === "AbortError") {
      return new IntegrationError(
        "模型请求已取消（AbortError）：请求被中止，请重试并检查超时设置和网络。",
        true,
      );
    }
    switch (code) {
      case "ETIMEDOUT":
      case "UND_ERR_CONNECT_TIMEOUT":
      case "UND_ERR_HEADERS_TIMEOUT":
      case "UND_ERR_BODY_TIMEOUT":
        return timeoutError(timeoutMs, code);
      case "ENOTFOUND":
      case "EAI_AGAIN":
        return new IntegrationError(
          `模型域名解析失败（${code}）：请检查模型地址、DNS、代理和网络连接。`,
          true,
        );
      case "ECONNREFUSED":
      case "ENETUNREACH":
      case "EHOSTUNREACH":
        return new IntegrationError(
          `模型网络连接失败（${code}）：请确认模型服务已启动，地址、端口及代理配置正确。`,
          true,
        );
      case "ECONNRESET":
      case "EPIPE":
      case "UND_ERR_SOCKET":
        return new IntegrationError(
          `模型网络连接中断（${code}）：请检查代理和网络稳定性后重试。`,
          true,
        );
      case "CERT_HAS_EXPIRED":
      case "DEPTH_ZERO_SELF_SIGNED_CERT":
      case "UNABLE_TO_VERIFY_LEAF_SIGNATURE":
      case "SELF_SIGNED_CERT_IN_CHAIN":
      case "ERR_TLS_CERT_ALTNAME_INVALID":
        return new IntegrationError(
          `模型 TLS 证书校验失败（${code}）：请检查服务证书、系统时间和代理证书信任配置。`,
          false,
        );
    }
  }
  return undefined;
}

function httpError(status: number): IntegrationError {
  const prefix = (category: string) => `模型${category}（HTTP ${status}）：`;
  switch (status) {
    case 401:
      return new IntegrationError(
        prefix("鉴权失败") + "API key 无效或已失效，请检查模型连接中的 API key。",
        false,
        status,
      );
    case 403:
      return new IntegrationError(
        prefix("权限不足") + "当前凭据无权使用该模型，请检查账号、模型权限与服务商限制。",
        false,
        status,
      );
    case 404:
      return new IntegrationError(
        prefix("地址或模型不存在") +
          "请检查 API base URL、模型标识及 Chat Completions 路径是否正确。",
        false,
        status,
      );
    case 400:
    case 422:
      return new IntegrationError(
        prefix("请求不兼容") +
          "接口未接受请求参数、上下文大小或 JSON Schema 结构化输出，请检查模型兼容性或缩小 PR。",
        false,
        status,
      );
    case 429:
      return new IntegrationError(
        prefix("限流或额度不足") + "请检查账号额度和请求频率，稍后重试。",
        true,
        status,
      );
    default:
      return new IntegrationError(
        prefix(status >= 500 ? "服务异常" : "HTTP 请求失败") +
          (status >= 500 ? "服务商暂时无法完成请求，请稍后重试。" : "请检查模型连接配置后重试。"),
        status >= 500 || status === 408 || status === 409,
        status,
      );
  }
}

/** Produces diagnostics without retaining SDK messages, requests, responses or causes. */
export function normalizeModelError(
  error: unknown,
  timeoutMs: number,
  apiKey?: string,
): IntegrationError {
  const chain = causeChain(error);
  let normalized: IntegrationError;
  const api = chain.find(
    (cause) => APICallError.isInstance(cause) && cause.statusCode !== undefined,
  );
  if (
    APICallError.isInstance(api) &&
    typeof api.statusCode === "number" &&
    Number.isInteger(api.statusCode) &&
    api.statusCode >= 400 &&
    api.statusCode <= 599
  ) {
    normalized = httpError(api.statusCode);
  } else {
    normalized =
      networkError(chain, timeoutMs) ??
      outputError(chain) ??
      new IntegrationError("模型请求失败：发生未识别错误，请稍后重试或检查模型连接。", true);
  }
  return new IntegrationError(
    apiKey ? normalized.message.replaceAll(apiKey, "[已隐藏]") : normalized.message,
    normalized.retryable,
    normalized.status,
  );
}
