import type { NormalizedEvent, Provider } from "@review/contracts";
import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import type { WebhookPort } from "../ports.js";
import { IntegrationError } from "./integration-error.js";

type Value = Record<string, unknown>;
const object = (value: unknown): Value =>
  value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Value) : {};
const str = (value: unknown) => (typeof value === "string" ? value : "");
const id = (value: unknown) =>
  typeof value === "number" || typeof value === "string" ? String(value) : "";
const number = (value: unknown) =>
  typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
const list = (value: unknown): Value[] => (Array.isArray(value) ? value.map(object) : []);
const header = (headers: Record<string, string | undefined>, name: string) =>
  Object.entries(headers).find(([key]) => key.toLowerCase() === name)?.[1] ?? "";
const equalSecret = (a: string, b: string) =>
  timingSafeEqual(createHash("sha256").update(a).digest(), createHash("sha256").update(b).digest());

export class WebhookAdapter implements WebhookPort {
  verify(
    provider: Provider,
    headers: Record<string, string | undefined>,
    rawBody: Buffer,
    secret: string,
  ): boolean {
    if (!secret) {
      return false;
    }
    if (provider === "gitlab") {
      const incoming = header(headers, "x-gitlab-token");
      return incoming.length > 0 && equalSecret(incoming, secret);
    }
    if (provider !== "github") {
      return false;
    }
    const signature = header(headers, "x-hub-signature-256");
    if (!/^sha256=[a-fA-F0-9]{64}$/.test(signature)) {
      return false;
    }
    const expected = createHmac("sha256", secret).update(rawBody).digest();
    return timingSafeEqual(expected, Buffer.from(signature.slice(7), "hex"));
  }

  normalize(
    provider: Provider,
    headers: Record<string, string | undefined>,
    payload: unknown,
  ): NormalizedEvent[] {
    const data = object(payload);
    return provider === "github"
      ? this.github(headers, data)
      : provider === "gitlab"
        ? this.gitlab(headers, data)
        : [];
  }

  private events(
    provider: Provider,
    headers: Record<string, string | undefined>,
    repository: string,
    events: Array<Omit<NormalizedEvent, "id" | "repository">>,
  ): NormalizedEvent[] {
    if (events.length === 0) {
      return [];
    }
    if (!repository) {
      throw new IntegrationError("Webhook 缺少仓库标识");
    }
    const delivery =
      provider === "github"
        ? header(headers, "x-github-delivery")
        : header(headers, "webhook-id") ||
          header(headers, "idempotency-key") ||
          header(headers, "x-gitlab-webhook-uuid") ||
          header(headers, "x-gitlab-event-uuid");
    if (!delivery) {
      throw new IntegrationError("Webhook 缺少外部投递标识，无法可靠去重");
    }
    return events.map((event, index) => ({
      ...event,
      repository,
      id: `${provider}:${delivery}:${event.type}:${event.number ?? index}:${event.commentId ?? event.headSha ?? ""}:${event.state ?? ""}`,
    }));
  }

  private github(headers: Record<string, string | undefined>, data: Value): NormalizedEvent[] {
    const event = header(headers, "x-github-event");
    const repository = str(object(data.repository).full_name);
    const action = str(data.action);
    const pr = object(data.pull_request);
    const prNumber = number(pr.number) ?? number(data.number);
    const headSha = str(object(pr.head).sha) || undefined;
    if (event === "pull_request") {
      if (!prNumber) {
        return [];
      }
      if (["opened", "reopened", "synchronize", "ready_for_review"].includes(action)) {
        return this.events("github", headers, repository, [
          { type: "pr", number: prNumber, headSha, state: "open" },
        ]);
      }
      if (action === "closed") {
        return this.events("github", headers, repository, [
          {
            type: "closed",
            number: prNumber,
            headSha,
            state: pr.merged === true ? "merged" : "closed",
          },
        ]);
      }
      return [];
    }
    if (event === "pull_request_review_comment" && action === "created") {
      const comment = object(data.comment);
      const author = object(comment.user);
      if (!prNumber || !id(comment.id)) {
        return [];
      }
      return this.events("github", headers, repository, [
        {
          type: "reply",
          number: prNumber,
          headSha,
          body: str(comment.body),
          commentId: `review:${id(comment.id)}`,
          threadId: `review:${id(comment.in_reply_to_id) || id(comment.id)}`,
          author: str(author.login),
          isBot: author.type === "Bot" || str(author.login).endsWith("[bot]"),
        },
      ]);
    }
    if (event === "issue_comment" && action === "created") {
      const issue = object(data.issue);
      const comment = object(data.comment);
      const author = object(comment.user);
      if (!issue.pull_request || !number(issue.number) || !id(comment.id)) {
        return [];
      }
      return this.events("github", headers, repository, [
        {
          type: "reply",
          number: number(issue.number),
          body: str(comment.body),
          commentId: `issue:${id(comment.id)}`,
          author: str(author.login),
          isBot: author.type === "Bot" || str(author.login).endsWith("[bot]"),
        },
      ]);
    }
    if (["check_run", "check_suite", "workflow_run"].includes(event)) {
      const check = object(data[event]);
      const sha = str(check.head_sha);
      if (!sha) {
        return [];
      }
      const state = str(check.conclusion) || str(check.status);
      const prs = list(check.pull_requests);
      return this.events(
        "github",
        headers,
        repository,
        prs.length
          ? prs
              .filter((item) => number(item.number))
              .map((item) => ({
                type: "ci",
                number: number(item.number),
                headSha: sha,
                state,
              }))
          : [{ type: "ci", headSha: sha, state }],
      );
    }
    if (event === "status" && str(data.sha)) {
      return this.events("github", headers, repository, [
        { type: "ci", headSha: str(data.sha), state: str(data.state) },
      ]);
    }
    return [];
  }

  private gitlab(headers: Record<string, string | undefined>, data: Value): NormalizedEvent[] {
    const event = header(headers, "x-gitlab-event");
    const repository = str(object(data.project).path_with_namespace);
    const attributes = object(data.object_attributes);
    const action = str(attributes.action);
    if (event === "Merge Request Hook" && data.object_kind === "merge_request") {
      const prNumber = number(attributes.iid);
      const headSha = str(object(attributes.last_commit).id) || undefined;
      if (!prNumber) {
        return [];
      }
      if (["open", "reopen"].includes(action) || (action === "update" && str(attributes.oldrev))) {
        return this.events("gitlab", headers, repository, [
          { type: "pr", number: prNumber, headSha, state: "open" },
        ]);
      }
      if (["merge", "close"].includes(action)) {
        return this.events("gitlab", headers, repository, [
          {
            type: "closed",
            number: prNumber,
            headSha,
            state: action === "merge" ? "merged" : "closed",
          },
        ]);
      }
      return [];
    }
    if (
      event === "Note Hook" &&
      data.object_kind === "note" &&
      attributes.noteable_type === "MergeRequest" &&
      (!action || action === "create")
    ) {
      const pr = object(data.merge_request);
      const user = object(data.user);
      if (!number(pr.iid) || !id(attributes.id) || attributes.system === true) {
        return [];
      }
      return this.events("gitlab", headers, repository, [
        {
          type: "reply",
          number: number(pr.iid),
          headSha: str(object(pr.last_commit).id) || undefined,
          body: str(attributes.note),
          commentId: `note:${id(attributes.id)}`,
          threadId: str(attributes.discussion_id) || undefined,
          author: str(user.username),
          isBot: user.bot === true,
        },
      ]);
    }
    if (event === "Pipeline Hook" && data.object_kind === "pipeline") {
      const sha = str(attributes.sha) || str(object(data.commit).id);
      if (!sha) {
        return [];
      }
      return this.events("gitlab", headers, repository, [
        {
          type: "ci",
          number: number(object(data.merge_request).iid),
          headSha: sha,
          state: str(attributes.status),
        },
      ]);
    }
    if (event === "Job Hook" && data.object_kind === "build") {
      const sha = str(data.sha) || str(object(data.commit).sha);
      if (!sha) {
        return [];
      }
      return this.events("gitlab", headers, repository, [
        { type: "ci", headSha: sha, state: str(data.build_status) },
      ]);
    }
    return [];
  }
}
