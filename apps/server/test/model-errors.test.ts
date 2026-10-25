import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { reviewResultSchema } from "@review/contracts";
import {
  APICallError,
  generateText,
  NoObjectGeneratedError,
  NoOutputGeneratedError,
  Output,
  TypeValidationError,
} from "ai";
import { describe, expect, it } from "vitest";
import { z } from "zod";
import { IntegrationError } from "../src/integrations/integration-error.js";
import { normalizeModelError } from "../src/integrations/model-error.js";

const privateValue = "private-api-key-do-not-display";

function apiError(statusCode: number) {
  return new APICallError({
    message: `Provider rejected ${privateValue}`,
    url: `https://private-model.example/v1?key=${privateValue}`,
    requestBodyValues: { prompt: "private prompt" },
    statusCode,
    responseHeaders: { authorization: privateValue },
    responseBody: `private output ${privateValue}`,
  });
}

function expectSafe(error: IntegrationError) {
  expect(error).toBeInstanceOf(IntegrationError);
  const visible = `${error.message}\n${error.stack}\n${JSON.stringify(error)}`;
  for (const secret of [
    privateValue,
    "private-model.example",
    "private prompt",
    "private output",
  ]) {
    expect(visible).not.toContain(secret);
  }
  expect(error.cause).toBeUndefined();
}

async function outputError(content: string, finishReason = "stop"): Promise<unknown> {
  const provider = createOpenAICompatible({
    name: "fixture",
    baseURL: "https://private-model.example/v1",
    apiKey: privateValue,
    supportsStructuredOutputs: true,
    fetch: async () =>
      new Response(
        JSON.stringify({
          id: "private-response-id",
          object: "chat.completion",
          created: 1,
          model: "fixture",
          choices: [
            { index: 0, message: { role: "assistant", content }, finish_reason: finishReason },
          ],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
        }),
        { headers: { "content-type": "application/json", "x-private-token": privateValue } },
      ),
  });
  try {
    await generateText({
      model: provider.chatModel("fixture"),
      prompt: "private prompt",
      output: Output.object({ schema: reviewResultSchema }),
      maxRetries: 0,
    });
  } catch (error) {
    return error;
  }
  throw new Error("Fixture should have produced an SDK output error");
}

describe("safe model error diagnostics", () => {
  it("identifies an invalid API key without exposing the SDK request or response", () => {
    const error = normalizeModelError(apiError(401), 120_000, privateValue);

    expect(error.message).toContain("鉴权失败");
    expect(error.message).toContain("HTTP 401");
    expect(error.message).toContain("API key");
    expect(error.status).toBe(401);
    expect(error.retryable).toBe(false);
    expectSafe(error);
  });

  it.each([
    [403, "权限不足", "权限", false],
    [404, "地址或模型不存在", "模型标识", false],
    [400, "请求不兼容", "结构化输出", false],
    [422, "请求不兼容", "结构化输出", false],
    [429, "限流或额度不足", "额度", true],
    [500, "服务异常", "稍后重试", true],
    [503, "服务异常", "稍后重试", true],
  ])(
    "explains HTTP %i with an actionable safe diagnosis",
    (status, category, action, retryable) => {
      const error = normalizeModelError(apiError(status), 120_000);

      expect(error.message).toContain(category);
      expect(error.message).toContain(action);
      expect(error.message).toContain(`HTTP ${status}`);
      expect(error.status).toBe(status);
      expect(error.retryable).toBe(retryable);
      expectSafe(error);
    },
  );

  it.each(["TimeoutError", "AbortError"])(
    "explains %s without including its private message",
    (name) => {
      const source = new DOMException(`private output ${privateValue}`, name);
      const error = normalizeModelError(source, 120_000);

      expect(error.message).toContain(name === "TimeoutError" ? "请求超时" : "请求已取消");
      if (name === "TimeoutError") {
        expect(error.message).toContain("120 秒");
      }
      expect(error.retryable).toBe(true);
      expectSafe(error);
    },
  );

  it.each([
    ["ENOTFOUND", "域名解析失败"],
    ["EAI_AGAIN", "域名解析失败"],
    ["ECONNREFUSED", "网络连接失败"],
    ["ECONNRESET", "网络连接中断"],
    ["ETIMEDOUT", "请求超时"],
    ["UND_ERR_CONNECT_TIMEOUT", "请求超时"],
    ["UND_ERR_HEADERS_TIMEOUT", "请求超时"],
    ["UND_ERR_SOCKET", "网络连接中断"],
    ["CERT_HAS_EXPIRED", "证书校验失败"],
    ["DEPTH_ZERO_SELF_SIGNED_CERT", "证书校验失败"],
  ])("preserves only the known network code %s across SDK cause wrappers", (code, category) => {
    const network = Object.assign(new Error(`private output ${privateValue}`), { code });
    const source = new APICallError({
      message: privateValue,
      url: "https://private-model.example/v1",
      requestBodyValues: { prompt: "private prompt" },
      cause: new TypeError(privateValue, { cause: network }),
    });
    const error = normalizeModelError(source, 120_000);

    expect(error.message).toContain(category);
    expect(error.message).toContain(code);
    expectSafe(error);
  });

  it("recognizes SDK output token truncation before its JSON parse cause", async () => {
    const source = await outputError(`{"summary":"private output ${privateValue}`, "length");
    expect(NoObjectGeneratedError.isInstance(source)).toBe(true);
    const error = normalizeModelError(source, 120_000);

    expect(error.message).toContain("输出被截断");
    expect(error.message).toContain("finishReason=length");
    expect(error.message).toContain("输出 token 上限");
    expectSafe(error);
  });

  it("explains malformed JSON separately from schema validation", async () => {
    const source = await outputError(`private output ${privateValue}`);
    expect(NoObjectGeneratedError.isInstance(source)).toBe(true);
    const error = normalizeModelError(source, 120_000);

    expect(error.message).toContain("结构化输出无效");
    expect(error.message).toContain("JSON 解析失败");
    expectSafe(error);
  });

  it("reports safe field paths and expected types from a real SDK schema failure", async () => {
    const source = await outputError(
      JSON.stringify({
        summary: "private output",
        findings: [
          {
            title: privateValue,
            body: privateValue,
            path: "src/private.ts",
            line: privateValue,
            severity: "medium",
            category: "bug",
            evidence: privateValue,
          },
        ],
        resolvedFindingIds: [],
      }),
    );
    expect(NoObjectGeneratedError.isInstance(source)).toBe(true);
    const error = normalizeModelError(source, 120_000);

    expect(error.message).toContain("结构化输出无效");
    expect(error.message).toContain("findings[0].line");
    expect(error.message).toContain("number");
    expectSafe(error);
  });

  it("explains an empty SDK result and preserves actionable causes when present", () => {
    const empty = normalizeModelError(
      new NoOutputGeneratedError({ message: privateValue }),
      120_000,
    );
    expect(empty.message).toContain("未生成输出");
    expectSafe(empty);

    const unauthorized = normalizeModelError(
      new NoOutputGeneratedError({ cause: apiError(401) }),
      120_000,
    );
    expect(unauthorized.status).toBe(401);
    expectSafe(unauthorized);
  });

  it("never exposes Zod messages, values, unknown field names or arbitrary expected types", () => {
    const validation = new z.ZodError([
      {
        code: "invalid_type",
        path: [privateValue, "findings", 0, "title"],
        expected: "string",
        message: `private output ${privateValue}`,
      },
      {
        code: "invalid_type",
        path: ["summary"],
        expected: privateValue,
        message: `private output ${privateValue}`,
      },
    ]);
    const error = normalizeModelError(
      new TypeValidationError({ value: { [privateValue]: privateValue }, cause: validation }),
      120_000,
    );

    expect(error.message).toContain("结构化输出无效");
    expect(error.message).toContain("string");
    expectSafe(error);
  });

  it("keeps unknown errors generic and handles cyclic cause chains safely", () => {
    const source = Object.assign(new Error(`private output ${privateValue}`), {
      code: privateValue,
    });
    source.cause = source;
    const error = normalizeModelError(source, 120_000);

    expect(error.message).toContain("未识别错误");
    expect(error.message).not.toContain("鉴权失败");
    expectSafe(error);
  });
});
