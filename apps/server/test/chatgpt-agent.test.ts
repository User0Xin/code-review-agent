import type { ModelConnection } from "@review/contracts";
import { describe, expect, it, vi } from "vitest";
import { AiReviewAgent } from "../src/integrations/agent.js";
import { DomainError } from "../src/common/domain-error.js";
import type { ReviewInput } from "../src/ports.js";
import { fixtureModel, fixtureProject, fixtureSnapshot } from "./fixtures/stored-workspace.js";

const token = "private-chatgpt-oauth-token";
const legacyKey = "private-unused-api-key";
const model: ModelConnection = {
  ...fixtureModel,
  authMode: "chatgpt",
  chatgptAccountId: "account-1",
  // The OAuth path must ignore configurable API endpoints entirely.
  baseUrl: "https://untrusted-model.example/v1",
  model: "gpt-6.1-sol",
};
const input: ReviewInput = {
  project: fixtureProject,
  snapshot: fixtureSnapshot,
  priorFindings: [],
  experiences: [],
  model,
  apiKey: legacyKey,
};
const review = { summary: "当前材料未发现可靠问题。", findings: [], resolvedFindingIds: [] };
const completed = { type: "response.completed", response: {} };

function responseEvents(content: string, terminal: unknown[] = [completed]) {
  return [
    { type: "response.created", response: { id: "resp-1", created_at: 1, model: model.model } },
    { type: "response.output_item.added", output_index: 0, item: { type: "message", id: "msg-1" } },
    { type: "response.output_text.delta", item_id: "msg-1", output_index: 0, delta: content },
    { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "msg-1" } },
    ...terminal,
  ];
}

function fixture(content: string, terminal: unknown[] = [completed]) {
  const requests: Array<{
    url: string;
    headers: Headers;
    body: Record<string, unknown>;
    redirect?: RequestRedirect;
  }> = [];
  const fetcher: typeof fetch = async (url, init) => {
    requests.push({
      url: String(url),
      headers: new Headers(init?.headers),
      body: JSON.parse(String(init?.body)) as Record<string, unknown>,
      redirect: init?.redirect,
    });
    const data = responseEvents(content, terminal)
      .map((event) => `data: ${JSON.stringify(event)}\n\n`)
      .join("");
    const encoded = new TextEncoder().encode(data);
    // Deliberately split SSE events and UTF-8 characters across network chunks.
    return new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          for (let offset = 0; offset < encoded.length; offset += 19) {
            controller.enqueue(encoded.slice(offset, offset + 19));
          }
          controller.close();
        },
      }),
      { headers: { "content-type": "text/event-stream" } },
    );
  };
  const resolveToken = vi.fn(async (_accountId: string) => token);
  return {
    requests,
    resolveToken,
    agent: new AiReviewAgent({ fetch: fetcher, chatgptAccessToken: resolveToken }),
  };
}

function expectSafeRequest(request: ReturnType<typeof fixture>["requests"][number]) {
  expect(request.url).toBe("https://api.openai.com/v1/responses");
  expect(request.redirect).toBe("error");
  expect(request.headers.get("authorization")).toBe(`Bearer ${token}`);
  expect(request.body).toMatchObject({ model: model.model, store: false, stream: true });
  expect(Array.isArray(request.body.input)).toBe(true);
  expect(JSON.stringify(request.body.input)).toContain('"role":"developer"');
  expect(JSON.stringify(request.body.input)).not.toContain('"role":"system"');
  for (const field of [
    "max_output_tokens",
    "temperature",
    "top_p",
    "background",
    "conversation",
    "metadata",
    "previous_response_id",
    "tools",
    "user",
    "prompt_cache_retention",
    "safety_identifier",
  ]) {
    expect(request.body).not.toHaveProperty(field);
  }
  expect(JSON.stringify(request.body)).not.toContain(token);
  expect(JSON.stringify(request.body)).not.toContain(legacyKey);
}

describe("ChatGPT account inference through the official Responses SDK", () => {
  it("reviews with the live selected account token and strict JSON Schema over streamed Responses", async () => {
    const f = fixture(JSON.stringify(review));
    expect(await f.agent.review(input)).toEqual(review);
    expect(f.resolveToken).toHaveBeenCalledWith("account-1");
    expectSafeRequest(f.requests[0]!);
    expect(f.requests[0]!.body.text).toMatchObject({
      format: { type: "json_schema", strict: true },
    });
    expect(JSON.stringify(f.requests[0]!.body.input)).toContain("精确原文片段");
  });

  it("uses streamed Responses for reply, chat, summaries, and structured connection tests", async () => {
    const reply = fixture(
      JSON.stringify({ body: "需补充具体证据。", verdict: "uncertain", evidence: null }),
    );
    expect(await reply.agent.reply({ ...input, message: "为何有问题？" })).toEqual({
      body: "需补充具体证据。",
      verdict: "uncertain",
    });
    expectSafeRequest(reply.requests[0]!);

    const chat = fixture("结合当前 PR，尚无足够证据。");
    expect(
      await chat.agent.chat({
        ...input,
        message: "解释一下",
        history: [{ role: "user", body: "前一问" }],
      }),
    ).toBe("结合当前 PR，尚无足够证据。");
    expectSafeRequest(chat.requests[0]!);
    expect(JSON.stringify(chat.requests[0]!.body.input)).toContain("前一问");
    expect(chat.requests[0]!.body).not.toHaveProperty("text");

    const summaryBody = `### 适用范围

当前模块。

### 支持证据

- session-1 已验证的材料。

### 建议

- 后续审查核对同类代码。

### 局限

尚不能推广到其他模块。`;
    const summary = fixture(
      JSON.stringify({
        proposals: [{ kind: "experience", body: summaryBody }],
      }),
    );
    expect(
      await summary.agent.summarize({
        project: input.project,
        model,
        apiKey: legacyKey,
        sourceSessionIds: ["session-1"],
        evidence: "session-1 已验证的材料",
      }),
    ).toEqual([{ kind: "experience", body: summaryBody }]);
    expectSafeRequest(summary.requests[0]!);
    const summaryPrompt = JSON.stringify(summary.requests[0]!.body.input);
    for (const heading of ["### 适用范围", "### 支持证据", "### 建议", "### 局限"]) {
      expect(summaryPrompt).toContain(heading);
    }
    expect(summaryPrompt).toContain("标题、段落、列表之间留空行");
    expect(summaryPrompt).toContain("sourceSessionIds 的完整 ID");

    const test = fixture('{"ok":true}');
    expect(await test.agent.test({ ...model, enabled: false }, legacyKey)).toContain(
      "结构化输出验证成功",
    );
    expectSafeRequest(test.requests[0]!);
    expect(test.requests[0]!.body.text).toMatchObject({ format: { type: "json_schema" } });
  });

  it("resolves refreshed credentials separately for every request", async () => {
    const f = fixture('{"ok":true}');
    f.resolveToken.mockResolvedValueOnce(token).mockResolvedValueOnce("renewed-access-token");
    await f.agent.test(model, legacyKey);
    await f.agent.test(model, legacyKey);
    expect(f.resolveToken).toHaveBeenCalledTimes(2);
    expect(f.requests[1]!.headers.get("authorization")).toBe("Bearer renewed-access-token");
  });

  it.each([
    [[], "未收到完成事件"],
    [
      [
        {
          type: "response.incomplete",
          response: { incomplete_details: { reason: "max_output_tokens" } },
        },
      ],
      "未完成或被截断",
    ],
    [
      [
        {
          type: "response.failed",
          sequence_number: 5,
          response: {
            error: {
              code: "subscription_sharing_usage_limit_exceeded",
              message: `${token} private response`,
            },
          },
        },
      ],
      "共享使用额度已用尽",
    ],
    [
      [
        {
          type: "response.failed",
          sequence_number: 5,
          response: {
            error: {
              code: "subscription_sharing_usage_unavailable",
              message: `${token} private response`,
            },
          },
        },
      ],
      "暂时无法共享订阅额度",
    ],
    [
      [{ type: "error", code: "server_error", message: `${token} private response` }],
      "流式请求失败",
    ],
  ])(
    "rejects interrupted/failed streams even after valid JSON text (%s)",
    async (terminal, diagnostic) => {
      const f = fixture(JSON.stringify(review), terminal);
      const error: unknown = await f.agent.review(input).catch((value: unknown) => value);
      expect(error).toBeInstanceOf(Error);
      expect(error).toMatchObject({ message: expect.stringContaining(diagnostic) });
      expect(JSON.stringify(error)).not.toContain(token);
      expect(JSON.stringify(error)).not.toContain("private response");
      if (error instanceof Error) {
        expect(error.cause).toBeUndefined();
      }
    },
  );

  it("rejects schema-invalid, invalid JSON, and foreign resolution IDs after completion", async () => {
    await expect(
      fixture('{"summary":"ok","findings":"wrong","resolvedFindingIds":[]}').agent.review(input),
    ).rejects.toThrow("结构化输出无效");
    await expect(fixture('{"summary":').agent.review(input)).rejects.toThrow("结构化输出无效");
    await expect(
      fixture(JSON.stringify({ ...review, resolvedFindingIds: ["foreign-session"] })).agent.review(
        input,
      ),
    ).rejects.toThrow("不属于本次会话");
  });

  it("keeps existing evidence guards for withdrawals and summary sources", async () => {
    const reply = fixture(
      JSON.stringify({ body: "撤回。", verdict: "withdraw", evidence: "invented" }),
    );
    expect((await reply.agent.reply({ ...input, message: "撤回" })).verdict).toBe("uncertain");
    const summary = fixture(
      JSON.stringify({ proposals: [{ kind: "standard", body: "无来源的规范。" }] }),
    );
    await expect(
      summary.agent.summarize({
        project: input.project,
        model,
        apiKey: legacyKey,
        sourceSessionIds: ["session-1"],
        evidence: "evidence",
      }),
    ).rejects.toThrow("缺少可追溯");
  });

  it("refuses disabled/missing accounts and bounds context before resolving credentials", async () => {
    const f = fixture(JSON.stringify(review));
    await expect(f.agent.review({ ...input, model: { ...model, enabled: false } })).rejects.toThrow(
      "已停用",
    );
    await expect(
      f.agent.review({ ...input, model: { ...model, chatgptAccountId: undefined } }),
    ).rejects.toThrow("账号未连接");
    await expect(
      new AiReviewAgent({
        fetch: async () => {
          throw new Error("must not fetch");
        },
        chatgptAccessToken: f.resolveToken,
        maxInputChars: 2,
      }).review(input),
    ).rejects.toThrow("上下文超过");
    expect(f.resolveToken).not.toHaveBeenCalled();
    expect(f.requests).toHaveLength(0);
    await expect(new AiReviewAgent({}).review(input)).rejects.toThrow("账号未连接");
  });

  it("preserves safe account recovery instructions when credential resolution fails", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const agent = new AiReviewAgent({
      fetch: fetcher,
      chatgptAccessToken: async () => {
        throw new DomainError("ChatGPT 账号未登录或授权已失效，请重新登录");
      },
    });
    await expect(agent.review(input)).rejects.toMatchObject({
      message: "ChatGPT 账号未登录或授权已失效，请重新登录",
      retryable: false,
    });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("reports HTTP authentication and network errors without token or body details", async () => {
    const rejected = new AiReviewAgent({
      chatgptAccessToken: async () => token,
      fetch: async () =>
        new Response(
          JSON.stringify({
            error: { message: `${token} private response`, type: "authentication_error" },
          }),
          { status: 401, headers: { "content-type": "application/json" } },
        ),
    });
    await expect(rejected.review(input)).rejects.toMatchObject({
      status: 401,
      message: expect.stringContaining("重新登录账号"),
    });
    const broken = new AiReviewAgent({
      chatgptAccessToken: async () => token,
      fetch: async () => {
        throw new Error(`${token} private response`);
      },
    });
    const error: unknown = await broken.review(input).catch((value: unknown) => value);
    expect(JSON.stringify(error)).not.toContain(token);
    expect(JSON.stringify(error)).not.toContain("private response");
  });
});
