import type { Job, NormalizedEvent } from "@review/contracts";
import { randomUUID } from "node:crypto";
import { DomainError } from "../common/domain-error.js";
import type { AgentPort, RepositoryPort } from "../ports.js";
import type { Store } from "../store.js";
import { ConversationService } from "./conversation.service.js";
import { EventService } from "./event.service.js";
import { PublicationService } from "./publication.service.js";
import { ReviewService } from "./review.service.js";
import { SummaryService } from "./summary.service.js";
import { timestamp } from "./utils.js";
import { WorkflowContext } from "./workflow-context.js";

/** Coordinates durable commands; each operation owns its implementation. */
export class Workflow {
  private readonly context: WorkflowContext;
  private readonly reviews: ReviewService;
  private readonly publications: PublicationService;
  private readonly conversations: ConversationService;
  private readonly summaries: SummaryService;
  private readonly events: EventService;

  constructor(
    private readonly store: Store,
    repository: RepositoryPort,
    agent: AgentPort,
  ) {
    this.context = new WorkflowContext(store, repository, agent);
    this.reviews = new ReviewService(this.context);
    this.publications = new PublicationService(this.context);
    this.conversations = new ConversationService(this.context, this.publications);
    this.summaries = new SummaryService(this.context);
    this.events = new EventService(this.context);
  }

  importPullRequest(projectId: string, number: number) {
    return this.context.importPullRequest(projectId, number);
  }

  acceptEvent(projectId: string, event: NormalizedEvent): Promise<void> {
    return this.events.acceptEvent(projectId, event);
  }

  queueReview(sessionId: string, trigger = "manual"): Job {
    const session = this.context.session(sessionId);
    if (session.state !== "open") {
      throw new DomainError("已关闭或合并的 PR 不能发起新审查");
    }
    this.context.model(this.context.project(session.projectId).modelId);
    return this.store.jobs.enqueue(
      "review",
      { sessionId, trigger },
      `review:manual:${randomUUID()}`,
    );
  }

  queuePublish(runId: string): Job {
    const run = this.context.run(runId);
    const session = this.context.session(run.sessionId);
    if (run.status !== "completed" || run.headSha !== session.headSha || session.state !== "open") {
      throw new DomainError("只能发布当前提交上已完成的审查");
    }
    return this.store.jobs.enqueue("publish", { runId }, `publish:${runId}`);
  }

  queueMessage(
    sessionId: string,
    body: string,
    visibility: "internal" | "external",
    findingId?: string,
  ): Job {
    const session = this.context.session(sessionId);
    if (!body.trim()) {
      throw new DomainError("消息不能为空");
    }
    const finding = findingId ? this.store.findings.get(findingId) : undefined;
    if (findingId && (!finding || finding.sessionId !== sessionId)) {
      throw new DomainError("发现不属于当前 PR");
    }
    if (visibility === "external" && findingId && (!finding?.remoteId || !finding.publishedAt)) {
      throw new DomainError("外部回复必须选择已经发布的原评论");
    }
    return this.store.transaction(() => {
      const key = `message:${randomUUID()}`;
      const payload = { sessionId, body, findingId, mode: "publish-user" as const };
      const job =
        visibility === "internal"
          ? this.store.jobs.enqueue("chat", payload, key)
          : this.store.jobs.enqueue("reply", payload, key);
      this.store.messages.save({
        id: `message:${job.id}:user`,
        projectId: session.projectId,
        sessionId,
        role: "user",
        body,
        visibility,
        ...(findingId ? { findingId } : {}),
        createdAt: timestamp(),
      });
      return job;
    });
  }

  queueSummary(projectId: string): Job {
    this.context.model(this.context.project(projectId).modelId);
    return this.store.jobs.enqueue(
      "summary",
      { projectId },
      `summary:${projectId}:${randomUUID()}`,
    );
  }

  async processJob(job: Job): Promise<void> {
    switch (job.kind) {
      case "event":
        await this.events.acceptEvent(job.payload.projectId, job.payload.event);
        break;
      case "review":
        await this.reviews.review(job);
        break;
      case "publish":
        await this.publications.publishRun(job.payload.runId);
        break;
      case "reply":
        await this.conversations.reply(job);
        break;
      case "chat":
        await this.conversations.chat(job);
        break;
      case "summary":
        await this.summaries.summarize(job);
        break;
      default:
        throw new DomainError("不支持的任务类型");
    }
  }
}
