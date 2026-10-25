import type { ModelConnection, Project, PullRequestSnapshot } from "@review/contracts";
import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { AiReviewAgent, HttpRepositoryAdapter, WebhookAdapter } from "../src/integrations/index.js";
import { newLinePositions } from "../src/integrations/repository.js";
import type { ReviewInput } from "../src/ports.js";

const project: Project = {
  id: "p",
  name: "Example",
  provider: "github",
  host: "https://github.com",
  repository: "owner/repo",
  modelId: "m",
  standard: "必须关闭响应体。",
  standardVersion: 1,
  ignorePatterns: [],
  autoReview: true,
  autoPublish: false,
  hasToken: true,
  hasWebhookSecret: true,
  createdAt: "",
  updatedAt: "",
};
const model: ModelConnection = {
  id: "m",
  name: "Fixture",
  baseUrl: "https://model.example/v1",
  model: "test-model",
  enabled: true,
  hasApiKey: true,
  createdAt: "",
  updatedAt: "",
};
const snapshot: PullRequestSnapshot = {
  number: 7,
  title: "Fix response",
  description: "Handle response",
  url: "https://github.com/owner/repo/pull/7",
  headSha: "head",
  baseSha: "base",
  branch: "fix",
  baseBranch: "main",
  state: "open",
  files: [
    {
      path: "after.ts",
      oldPath: "before.ts",
      patch: "@@ -10,2 +20,3 @@\n old\n+new\n last",
      content: "old\nnew\nlast",
      status: "renamed",
      additions: 1,
      deletions: 0,
    },
  ],
  truncated: false,
};
const input: ReviewInput = {
  project,
  snapshot,
  priorFindings: [],
  experiences: [],
  model,
  apiKey: "private-model-key",
};
const githubPr = {
  number: 7,
  title: "Fix response",
  body: "Describe intent",
  html_url: snapshot.url,
  state: "open",
  merged: false,
  changed_files: 1,
  head: { sha: "head", ref: "fix", repo: { full_name: "fork/repo" } },
  base: { sha: "base", ref: "main" },
};
const gitlab = {
  ...project,
  provider: "gitlab" as const,
  host: "https://gitlab.example",
  repository: "team/sub/repo",
};
const gitlabPr = {
  iid: 7,
  title: "Fix response",
  description: "Describe intent",
  web_url: "https://gitlab.example/team/sub/repo/-/merge_requests/7",
  state: "opened",
  changes_count: "1",
  source_branch: "fix",
  target_branch: "main",
  source_project_id: 42,
  diff_refs: { head_sha: "head", base_sha: "base", start_sha: "start" },
};
const json = (value: unknown, headers: Record<string, string> = {}, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
type Request = {
  url: URL;
  method: string;
  body?: Record<string, unknown>;
  headers: Headers;
};
function fixture(handler: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  const fetcher: typeof fetch = async (url, init) => {
    const request: Request = {
      url: new URL(String(url)),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      ...(typeof init?.body === "string"
        ? { body: JSON.parse(init.body) as Record<string, unknown> }
        : {}),
    };
    requests.push(request);
    return handler(request);
  };
  return { fetcher, requests };
}
const ghComment = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  body: "Comment",
  user: { login: "alice", type: "User" },
  created_at: "2026-10-22T10:00:00Z",
  ...extra,
});
const glNote = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  body: "Comment",
  author: { username: "alice" },
  created_at: "2026-10-22T10:00:00Z",
  ...extra,
});
function modelFixture(content: string, status = 200) {
  return fixture(() =>
    status === 200
      ? json({
          id: "chatcmpl-fixture",
          object: "chat.completion",
          created: 1,
          model: "test-model",
          choices: [
            {
              index: 0,
              message: { role: "assistant", content },
              finish_reason: "stop",
            },
          ],
          usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
        })
      : json({ error: { message: "private-model-key secret failure" } }, {}, status),
  );
}

describe("WebhookAdapter", () => {
  const adapter = new WebhookAdapter();
  it("checks GitHub SHA256 over the exact raw bytes and rejects malformed signatures", () => {
    const raw = Buffer.from('{ "text": "中文" }');
    const signature = `sha256=${createHmac("sha256", "secret").update(raw).digest("hex")}`;
    expect(adapter.verify("github", { "X-Hub-Signature-256": signature }, raw, "secret")).toBe(
      true,
    );
    expect(
      adapter.verify(
        "github",
        { "x-hub-signature-256": signature },
        Buffer.from('{"text":"中文"}'),
        "secret",
      ),
    ).toBe(false);
    expect(adapter.verify("github", { "x-hub-signature-256": "sha256=no" }, raw, "secret")).toBe(
      false,
    );
    expect(adapter.verify("github", {}, raw, "")).toBe(false);
  });
  it("validates GitLab tokens without assuming equal input lengths", () => {
    expect(
      adapter.verify("gitlab", { "X-Gitlab-Token": "secret" }, Buffer.from("{}"), "secret"),
    ).toBe(true);
    expect(
      adapter.verify("gitlab", { "x-gitlab-token": "secret-longer" }, Buffer.from("{}"), "secret"),
    ).toBe(false);
    expect(adapter.verify("gitlab", {}, Buffer.from("{}"), "secret")).toBe(false);
  });
  it("normalizes PR commits and closure, while ignoring title edits and foreign event headers", () => {
    const headers = {
      "x-github-event": "pull_request",
      "x-github-delivery": "delivery",
    };
    const payload = {
      repository: { full_name: project.repository },
      pull_request: githubPr,
      action: "synchronize",
    };
    expect(adapter.normalize("github", headers, payload)[0]!).toMatchObject({
      type: "pr",
      number: 7,
      headSha: "head",
    });
    expect(adapter.normalize("github", headers, { ...payload, action: "edited" })).toEqual([]);
    expect(adapter.normalize("gitlab", headers, payload)).toEqual([]);
    expect(
      adapter.normalize("github", headers, {
        ...payload,
        action: "closed",
        pull_request: { ...githubPr, merged: true },
      })[0]!,
    ).toMatchObject({ type: "closed", state: "merged" });
  });
  it("only accepts PR issue comments, preserves review roots, and rejects missing delivery IDs", () => {
    const payload = {
      action: "created",
      repository: { full_name: project.repository },
      issue: { number: 7 },
      comment: ghComment(18),
    };
    const headers = {
      "x-github-event": "issue_comment",
      "x-github-delivery": "delivery",
    };
    expect(adapter.normalize("github", headers, payload)).toEqual([]);
    expect(
      adapter.normalize("github", headers, {
        ...payload,
        issue: { number: 7, pull_request: {} },
      })[0]!,
    ).toMatchObject({ type: "reply", commentId: "issue:18" });
    expect(
      adapter.normalize(
        "github",
        { ...headers, "x-github-event": "pull_request_review_comment" },
        {
          ...payload,
          pull_request: githubPr,
          comment: ghComment(18, { in_reply_to_id: 10 }),
        },
      )[0]!,
    ).toMatchObject({ commentId: "review:18", threadId: "review:10" });
    expect(() =>
      adapter.normalize(
        "github",
        { "x-github-event": "issue_comment" },
        { ...payload, issue: { number: 7, pull_request: {} } },
      ),
    ).toThrow("投递标识");
  });
  it("correlates CI by commit without turning it into a review event", () => {
    const events = adapter.normalize(
      "github",
      { "x-github-event": "check_run", "x-github-delivery": "ci-1" },
      {
        repository: { full_name: project.repository },
        check_run: {
          head_sha: "head",
          status: "completed",
          conclusion: "success",
          pull_requests: [{ number: 7 }, { number: 8 }],
        },
      },
    );
    expect(events.map((event) => [event.type, event.number, event.state])).toEqual([
      ["ci", 7, "success"],
      ["ci", 8, "success"],
    ]);
    expect(events[0]!.id).not.toBe(events[1]!.id);
  });
  it("uses the retry-stable GitLab delivery ID and separates commits, replies, and pipelines", () => {
    const headers = {
      "x-gitlab-event": "Merge Request Hook",
      "webhook-id": "stable",
      "x-gitlab-webhook-uuid": "attempt-1",
    };
    const payload = {
      object_kind: "merge_request",
      project: { path_with_namespace: gitlab.repository },
      object_attributes: {
        iid: 7,
        action: "update",
        oldrev: "old",
        last_commit: { id: "head" },
      },
    };
    const a = adapter.normalize("gitlab", headers, payload);
    expect(a[0]!.type).toBe("pr");
    expect(
      adapter.normalize("gitlab", { ...headers, "x-gitlab-webhook-uuid": "attempt-2" }, payload)[0]!
        .id,
    ).toBe(a[0]!.id);
    expect(
      adapter.normalize("gitlab", headers, {
        ...payload,
        object_attributes: { ...payload.object_attributes, oldrev: undefined },
      }),
    ).toEqual([]);
    const reply = adapter.normalize(
      "gitlab",
      { ...headers, "x-gitlab-event": "Note Hook" },
      {
        object_kind: "note",
        project: payload.project,
        user: { username: "alice" },
        merge_request: { iid: 7 },
        object_attributes: {
          id: 88,
          noteable_type: "MergeRequest",
          note: "why?",
          discussion_id: "thread",
        },
      },
    );
    expect(reply[0]!).toMatchObject({
      type: "reply",
      commentId: "note:88",
      threadId: "thread",
    });
    const ci = adapter.normalize(
      "gitlab",
      { ...headers, "x-gitlab-event": "Pipeline Hook" },
      {
        object_kind: "pipeline",
        project: payload.project,
        object_attributes: { sha: "head", status: "failed" },
        merge_request: { iid: 7 },
      },
    );
    expect(ci[0]!).toMatchObject({
      type: "ci",
      headSha: "head",
      state: "failed",
      number: 7,
    });
  });
});

describe("HttpRepositoryAdapter", () => {
  it.each([
    { provider: "GitHub", repositoryProject: project, token: "", credentials: "empty" },
    { provider: "GitHub", repositoryProject: project, token: "  \t ", credentials: "blank" },
    { provider: "GitLab", repositoryProject: gitlab, token: "", credentials: "empty" },
    { provider: "GitLab", repositoryProject: gitlab, token: "  \t ", credentials: "blank" },
  ])(
    "allows unauthenticated $provider reads with $credentials credentials",
    async ({ repositoryProject, token }) => {
      const f = fixture(() => json([]));
      await new HttpRepositoryAdapter({ fetch: f.fetcher }).listPullRequests(
        repositoryProject,
        token,
      );
      const headers = f.requests[0]!.headers;
      expect(headers.has("authorization")).toBe(false);
      expect(headers.has("private-token")).toBe(false);
      if (repositoryProject.provider === "github") {
        expect(headers.get("x-github-api-version")).toBe("2022-11-28");
      }
    },
  );
  it.each([
    {
      provider: "GitHub",
      repositoryProject: project,
      header: "authorization",
      expected: "Bearer fixture-token",
    },
    {
      provider: "GitLab",
      repositoryProject: gitlab,
      header: "private-token",
      expected: "fixture-token",
    },
  ])("preserves authentication for $provider reads with a token", async (testCase) => {
    const f = fixture(() => json([]));
    await new HttpRepositoryAdapter({ fetch: f.fetcher }).listPullRequests(
      testCase.repositoryProject,
      "fixture-token",
    );
    expect(f.requests[0]!.headers.get(testCase.header)).toBe(testCase.expected);
  });
  it("checks only PR revision/state without fetching expensive diff or comments", async () => {
    const f = fixture(() => json({ ...gitlabPr, state: "merged" }));
    expect(
      await new HttpRepositoryAdapter({ fetch: f.fetcher }).getRevision(gitlab, "token", 7),
    ).toEqual({
      headSha: "head",
      baseSha: "base",
      startSha: "start",
      state: "merged",
    });
    expect(f.requests).toHaveLength(1);
    expect(f.requests[0]!.url.pathname).toContain("/merge_requests/7");
  });
  it("reads GitHub description, real patch and immutable fork content", async () => {
    const f = fixture(({ url }) => {
      if (url.pathname.endsWith("/pulls/7")) {
        return json(githubPr);
      }
      if (url.pathname.endsWith("/pulls/7/files")) {
        return json([
          {
            filename: "after.ts",
            previous_filename: "before.ts",
            patch: snapshot.files[0]!.patch,
            status: "renamed",
            additions: 1,
            deletions: 0,
          },
        ]);
      }
      expect(url.pathname).toBe("/repos/fork/repo/contents/after.ts");
      expect(url.searchParams.get("ref")).toBe("head");
      return json({
        encoding: "base64",
        content: Buffer.from("old\nnew\nlast").toString("base64"),
      });
    });
    const result = await new HttpRepositoryAdapter({
      fetch: f.fetcher,
    }).getPullRequest(project, "token", 7);
    expect(result).toMatchObject({
      description: "Describe intent",
      headSha: "head",
      truncated: false,
      files: [{ content: "old\nnew\nlast", patch: snapshot.files[0]!.patch }],
    });
    expect(
      f.requests.every((request) => request.headers.get("authorization") === "Bearer token"),
    ).toBe(true);
  });
  it("bounds surrounding content and marks limited coverage", async () => {
    const f = fixture(({ url }) =>
      url.pathname.endsWith("/pulls/7")
        ? json(githubPr)
        : url.pathname.endsWith("/files")
          ? json([
              {
                filename: "after.ts",
                patch: snapshot.files[0]!.patch,
                status: "modified",
              },
            ])
          : json({
              encoding: "base64",
              content: Buffer.from("x\n".repeat(10000)).toString("base64"),
            }),
    );
    const result = await new HttpRepositoryAdapter({
      fetch: f.fetcher,
      maxContextChars: 100,
    }).getPullRequest(project, "token", 7);
    expect(result.truncated).toBe(true);
    expect(result.files[0]!.content!.length).toBeLessThanOrEqual(100);
  });
  it("rejects a snapshot if the PR changes while files are being read", async () => {
    let reads = 0;
    const f = fixture(({ url }) =>
      url.pathname.endsWith("/pulls/7")
        ? json({
            ...githubPr,
            head: {
              ...githubPr.head,
              sha: ++reads === 1 ? "head" : "new-head",
            },
          })
        : url.pathname.endsWith("/files")
          ? json([])
          : json({}),
    );
    await expect(
      new HttpRepositoryAdapter({ fetch: f.fetcher }).getPullRequest(project, "token", 7),
    ).rejects.toMatchObject({
      retryable: true,
      message: expect.stringContaining("版本发生变化"),
    });
  });
  it("returns the latest closed state even when the commit refs did not change", async () => {
    let reads = 0;
    const f = fixture(({ url }) =>
      url.pathname.endsWith("/pulls/7")
        ? json({ ...githubPr, state: ++reads === 1 ? "open" : "closed" })
        : json([]),
    );
    expect(
      (await new HttpRepositoryAdapter({ fetch: f.fetcher }).getPullRequest(project, "token", 7))
        .state,
    ).toBe("closed");
  });
  it("reports GitLab diff refs as retryable rather than reviewing an empty change", async () => {
    const f = fixture(() => json({ ...gitlabPr, diff_refs: null }));
    await expect(
      new HttpRepositoryAdapter({ fetch: f.fetcher }).getPullRequest(gitlab, "token", 7),
    ).rejects.toMatchObject({
      retryable: true,
      message: expect.stringContaining("尚未就绪"),
    });
  });
  it("reads GitLab diff and head/base/start refs with source-project context", async () => {
    const f = fixture(({ url }) => {
      if (url.pathname.endsWith("/merge_requests/7")) {
        return json(gitlabPr);
      }
      if (url.pathname.endsWith("/diffs")) {
        return json(
          [
            {
              old_path: "before.ts",
              new_path: "after.ts",
              diff: snapshot.files[0]!.patch,
              renamed_file: true,
            },
          ],
          { "x-next-page": "" },
        );
      }
      expect(url.pathname).toBe("/api/v4/projects/42/repository/files/after.ts");
      return json({
        encoding: "base64",
        content: Buffer.from("old\nnew\nlast").toString("base64"),
      });
    });
    expect(
      await new HttpRepositoryAdapter({ fetch: f.fetcher }).getPullRequest(gitlab, "token", 7),
    ).toMatchObject({
      headSha: "head",
      baseSha: "base",
      startSha: "start",
      description: "Describe intent",
    });
  });
  it("paginates both GitHub comment types and fails explicitly at a pagination limit", async () => {
    const f = fixture(({ url }) => {
      const page = Number(url.searchParams.get("page"));
      if (url.pathname.includes("/issues/")) {
        return json(
          page === 1 ? [ghComment(1)] : [ghComment(2)],
          page === 1
            ? { link: '<https://api.github.com/next>; rel="next"' }
            : { link: '<https://api.github.com/previous>; rel="prev"' },
        );
      }
      return json([ghComment(3, { in_reply_to_id: 9 })]);
    });
    const comments = await new HttpRepositoryAdapter({
      fetch: f.fetcher,
    }).getComments(project, "token", 7);
    expect(comments.map((comment) => comment.id)).toEqual(["issue:1", "issue:2", "review:3"]);
    expect(comments[2]!.threadId).toBe("review:9");
    await expect(
      new HttpRepositoryAdapter({ fetch: f.fetcher, maxPages: 1 }).getComments(project, "token", 7),
    ).rejects.toThrow("未取得完整数据");
  });
  it("keeps GitLab note IDs distinct from discussion hashes", async () => {
    const f = fixture(() =>
      json(
        [
          {
            id: "thread-hash",
            notes: [glNote(11), glNote(12), glNote(13, { system: true })],
          },
        ],
        { "x-next-page": "" },
      ),
    );
    expect(
      await new HttpRepositoryAdapter({ fetch: f.fetcher }).getComments(gitlab, "token", 7),
    ).toMatchObject([
      { id: "note:11", threadId: "thread-hash" },
      { id: "note:12", replyToId: "note:11" },
    ]);
  });
  it("posts GitHub inline comments on the RIGHT side and refuses guessed line positions", async () => {
    const f = fixture(({ body }) => json(ghComment(10, { path: body?.path, line: body?.line })));
    const adapter = new HttpRepositoryAdapter({ fetch: f.fetcher });
    const result = await adapter.publishComment(project, "token", snapshot, {
      body: "Check",
      path: "after.ts",
      line: 21,
    });
    expect(f.requests[0]!.body).toMatchObject({
      commit_id: "head",
      path: "after.ts",
      line: 21,
      side: "RIGHT",
    });
    expect(result.id).toBe("review:10");
    await expect(
      adapter.publishComment(project, "token", snapshot, {
        body: "Check",
        path: "after.ts",
        line: 500,
      }),
    ).rejects.toThrow("禁止猜测");
    expect(f.requests).toHaveLength(1);
  });
  it("maps renamed GitLab context lines to their distinct old/new lines", async () => {
    const f = fixture(() => json({ id: "thread", notes: [glNote(10)] }));
    const adapter = new HttpRepositoryAdapter({ fetch: f.fetcher });
    await adapter.publishComment(
      gitlab,
      "token",
      { ...snapshot, startSha: "start" },
      { body: "Check context", path: "after.ts", line: 22 },
    );
    expect(f.requests[0]!.body?.position).toEqual({
      position_type: "text",
      base_sha: "base",
      start_sha: "start",
      head_sha: "head",
      new_path: "after.ts",
      old_path: "before.ts",
      new_line: 22,
      old_line: 11,
    });
    await adapter.publishComment(
      gitlab,
      "token",
      { ...snapshot, startSha: "start" },
      { body: "Check added", path: "after.ts", line: 21 },
    );
    expect(f.requests[1]!.body?.position).not.toHaveProperty("old_line");
  });
  it("replies to the GitHub root review comment, validates thread ownership, and never casts issue IDs", async () => {
    const f = fixture(({ url, method }) =>
      method === "POST"
        ? json(ghComment(12, { in_reply_to_id: 10 }))
        : url.pathname.includes("/issues/")
          ? json([])
          : json([ghComment(10), ghComment(11, { in_reply_to_id: 10 })]),
    );
    const adapter = new HttpRepositoryAdapter({ fetch: f.fetcher });
    await adapter.publishComment(project, "token", snapshot, {
      body: "Reply",
      replyToId: "review:11",
      threadId: "review:10",
    });
    expect(f.requests.find((request) => request.method === "POST")?.url.pathname).toBe(
      "/repos/owner/repo/pulls/7/comments/10/replies",
    );
    await expect(
      adapter.publishComment(project, "token", snapshot, {
        body: "Reply",
        replyToId: "review:11",
        threadId: "review:999",
      }),
    ).rejects.toThrow("不匹配");
    await expect(
      adapter.publishComment(project, "token", snapshot, {
        body: "Reply",
        replyToId: "123",
      }),
    ).rejects.toThrow("评论类型");
  });
  it("publishes a linked timeline reply when GitHub has no native thread", async () => {
    const f = fixture(({ url, method, body }) =>
      method === "POST"
        ? json(ghComment(3, { body: body?.body }))
        : url.pathname.includes("/issues/")
          ? json([ghComment(2)])
          : json([]),
    );
    const result = await new HttpRepositoryAdapter({
      fetch: f.fetcher,
    }).publishComment(project, "token", snapshot, {
      body: "Answer",
      replyToId: "issue:2",
    });
    const posted = f.requests.find((request) => request.method === "POST")!;
    expect(posted.url.pathname).toBe("/repos/owner/repo/issues/7/comments");
    expect(posted.body?.body).toContain("#issuecomment-2");
    expect(result.replyToId).toBe("issue:2");
    expect(result.threadId).toBeUndefined();
  });
  it("replies to a verified GitLab discussion, not a note ID as a thread ID", async () => {
    const f = fixture(({ method }) =>
      method === "POST" ? json(glNote(12)) : json([{ id: "thread-hash", notes: [glNote(11)] }]),
    );
    const adapter = new HttpRepositoryAdapter({ fetch: f.fetcher });
    await adapter.publishComment(gitlab, "token", snapshot, {
      body: "Answer",
      replyToId: "note:11",
      threadId: "thread-hash",
    });
    expect(f.requests.find((request) => request.method === "POST")?.url.pathname).toContain(
      "/discussions/thread-hash/notes",
    );
    await expect(
      adapter.publishComment(gitlab, "token", snapshot, {
        body: "Answer",
        replyToId: "note:11",
        threadId: "11",
      }),
    ).rejects.toThrow("不匹配");
  });
  it("returns safe HTTP/network errors without response tokens", async () => {
    const f = fixture(() => json({ error: "private-repo-token" }, {}, 429));
    await expect(
      new HttpRepositoryAdapter({ fetch: f.fetcher }).listPullRequests(
        project,
        "private-repo-token",
      ),
    ).rejects.toMatchObject({
      status: 429,
      retryable: true,
      message: "代码平台请求失败（HTTP 429）",
    });
    const network: typeof fetch = async () => {
      throw new Error("private-repo-token in URL");
    };
    await expect(
      new HttpRepositoryAdapter({ fetch: network }).listPullRequests(project, "private-repo-token"),
    ).rejects.toThrow("代码平台网络请求失败");
  });
  it("enforces an actual abort signal timeout", async () => {
    const timeoutFetch: typeof fetch = async (_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("timeout private-token")));
      });
    await expect(
      new HttpRepositoryAdapter({
        fetch: timeoutFetch,
        timeoutMs: 5,
      }).listPullRequests(project, "token"),
    ).rejects.toMatchObject({
      retryable: true,
      message: "代码平台请求超时（配置上限 5 毫秒）：请检查网络、代理或平台状态后重试。",
    });
  });
  it("distinguishes invalid platform JSON without revealing its body", async () => {
    const f = fixture(() => new Response("private-repo-token in invalid JSON"));
    await expect(
      new HttpRepositoryAdapter({ fetch: f.fetcher }).listPullRequests(
        project,
        "private-repo-token",
      ),
    ).rejects.toMatchObject({
      retryable: true,
      message: "代码平台返回 JSON 无效：请检查平台 API 或代理响应后重试。",
    });
  });
  it("reports a whitelisted connection reset code without the original network error", async () => {
    const network: typeof fetch = async () => {
      throw new TypeError("private-repo-token in private URL", {
        cause: Object.assign(new Error("private-repo-token in private response"), {
          code: "ECONNRESET",
        }),
      });
    };
    const error: unknown = await new HttpRepositoryAdapter({ fetch: network })
      .listPullRequests(project, "private-repo-token")
      .catch((error: unknown) => error);
    expect(error).toBeInstanceOf(Error);
    expect(error).toMatchObject({
      retryable: true,
      message: "代码平台网络请求失败（ECONNRESET）：请检查网络、代理及平台连接后重试。",
    });
    if (error instanceof Error) {
      expect(error.cause).toBeUndefined();
      expect(`${error.message}\n${error.stack}\n${JSON.stringify(error)}`).not.toContain(
        "private-repo-token",
      );
    }
  });
  it("does not consider removed lines or hunk headers valid new-side positions", () => {
    expect([...newLinePositions("@@ -1,2 +1,1 @@\n-old\n context").entries()]).toEqual([[1, 2]]);
    expect(newLinePositions("not a patch").size).toBe(0);
  });
});

describe("AiReviewAgent using the real AI SDK provider with fixture HTTP", () => {
  it("uses configured endpoint/model and validates structured review output without publication tools", async () => {
    const result = {
      summary: "已审查",
      findings: [
        {
          title: "Close body",
          body: "必须关闭响应体",
          path: "after.ts",
          line: 21,
          severity: "medium",
          category: "bug",
          evidence: "new",
        },
      ],
      resolvedFindingIds: [],
    };
    const f = modelFixture(JSON.stringify(result));
    expect(await new AiReviewAgent({ fetch: f.fetcher }).review(input)).toEqual(result);
    expect(f.requests[0]!.url.href).toBe("https://model.example/v1/chat/completions");
    expect(f.requests[0]!.headers.get("authorization")).toBe("Bearer private-model-key");
    expect(f.requests[0]!.body?.model).toBe("test-model");
    expect(f.requests[0]!.body?.response_format).toMatchObject({
      type: "json_schema",
      json_schema: { schema: { type: "object" } },
    });
    expect(f.requests[0]!.body).not.toHaveProperty("tools");
    expect(JSON.stringify(f.requests[0]!.body?.messages)).toContain("精确原文片段");
    expect(JSON.stringify(f.requests[0]!.body?.messages)).toContain("简洁中文 Markdown");
    expect(JSON.stringify(f.requests[0]!.body?.messages)).toContain("未新增问题不等于全部通过");
    expect(JSON.stringify(f.requests[0]!.body?.messages)).not.toContain("private-model-key");
  });
  it("rejects malformed output and unknown resolution IDs rather than reporting a successful empty review", async () => {
    const malformed = modelFixture(
      JSON.stringify({
        summary: "ok",
        findings: "wrong",
        resolvedFindingIds: [],
      }),
    );
    await expect(new AiReviewAgent({ fetch: malformed.fetcher }).review(input)).rejects.toThrow(
      "结构化输出无效",
    );
    const unrelated = modelFixture(
      JSON.stringify({
        summary: "ok",
        findings: [],
        resolvedFindingIds: ["other-session-id"],
      }),
    );
    await expect(new AiReviewAgent({ fetch: unrelated.fetcher }).review(input)).rejects.toThrow(
      "不属于本次会话",
    );
  });
  it("does not withdraw a non-associated finding and asks for evidence before changing verdicts", async () => {
    const f = modelFixture(
      JSON.stringify({
        body: "请说明当前代码的实际行为。",
        verdict: "withdraw",
        evidence: null,
      }),
    );
    const result = await new AiReviewAgent({ fetch: f.fetcher }).reply({
      ...input,
      message: "不同意，撤回",
    });
    expect(result.verdict).toBe("uncertain");
    expect(JSON.stringify(f.requests[0]!.body?.messages)).toContain("不能因为开发者说");
  });
  it("summarizes readable Markdown proposals with traceable sources and rejects unsupported sources", async () => {
    const body = `### 适用范围

本模块中读取响应体的代码。

### 支持证据

- session-7 的修复表明响应体使用后需要关闭。

### 建议

- 验收时确认响应体已关闭。

### 局限

其他模块待验证。`;
    const f = modelFixture(
      JSON.stringify({
        proposals: [
          {
            kind: "experience",
            body,
          },
        ],
      }),
    );
    expect(
      await new AiReviewAgent({ fetch: f.fetcher }).summarize({
        project,
        model,
        apiKey: "key",
        evidence: "session-7 evidence",
        sourceSessionIds: ["session-7"],
      }),
    ).toEqual([{ kind: "experience", body }]);
    const prompt = JSON.stringify(f.requests[0]!.body?.messages);
    for (const heading of ["### 适用范围", "### 支持证据", "### 建议", "### 局限"]) {
      expect(prompt).toContain(heading);
    }
    expect(prompt).toContain("标题、段落、列表之间留空行");
    expect(prompt).toContain("使用无序列表");
    expect(prompt).toContain("反引号包裹");
    expect(prompt).toContain("sourceSessionIds 的完整 ID");
    const unsupported = modelFixture(
      JSON.stringify({
        proposals: [{ kind: "standard", body: "所有模块禁止异步代码。" }],
      }),
    );
    await expect(
      new AiReviewAgent({ fetch: unsupported.fetcher }).summarize({
        project,
        model,
        apiKey: "key",
        evidence: "evidence",
        sourceSessionIds: ["session-7"],
      }),
    ).rejects.toThrow("缺少可追溯");
  });
  it("redacts SDK error details, refuses disabled models and uses a bounded context", async () => {
    const failure = modelFixture("", 401);
    await expect(new AiReviewAgent({ fetch: failure.fetcher }).review(input)).rejects.toThrow(
      "鉴权失败（HTTP 401）",
    );
    await expect(
      new AiReviewAgent({ fetch: failure.fetcher }).review({
        ...input,
        model: { ...model, enabled: false },
      }),
    ).rejects.toThrow("已停用");
    await expect(
      new AiReviewAgent({ fetch: failure.fetcher, maxInputChars: 2 }).review(input),
    ).rejects.toThrow("上下文超过");
  });
  it("checks structured-output capability during the connection test", async () => {
    const f = modelFixture('{"ok":true}');
    expect(await new AiReviewAgent({ fetch: f.fetcher }).test(model, "key")).toContain(
      "结构化输出",
    );
    expect(f.requests[0]!.body?.response_format).toMatchObject({
      type: "json_schema",
    });
  });
});
