import type { Finding, JobOfKind, Message, Project, PullRequestSnapshot } from "@review/contracts";
import { DomainError } from "../common/domain-error.js";
import { requireEntity } from "../common/require-entity.js";
import { type PublicationService } from "./publication.service.js";
import { timestamp } from "./utils.js";
import { type WorkflowContext } from "./workflow-context.js";

type PublishedFinding = Finding & { remoteId: string; publishedAt: string };

export class ConversationService {
  constructor(
    private readonly context: WorkflowContext,
    private readonly publications: PublicationService,
  ) {}

  async chat(job: JobOfKind<"chat">): Promise<void> {
    const id = `message:${job.id}:assistant`;
    if (this.context.store.messages.get(id)) {
      return;
    }
    const { sessionId, body: userMessage } = job.payload;
    const input = await this.context.liveInput(sessionId);
    const currentMessageId = `message:${job.id}:user`;
    const messages = this.context.store.messages
      .listBySession(sessionId)
      .filter((message) => message.visibility === "internal");
    const currentIndex = messages.findIndex((message) => message.id === currentMessageId);
    const history = messages
      .slice(0, currentIndex >= 0 ? currentIndex : messages.length)
      .slice(-30)
      .flatMap((message) =>
        message.role === "user" || message.role === "assistant"
          ? [{ role: message.role, body: message.body }]
          : [],
      );
    const body = await this.context.agent.chat({ ...input, message: userMessage, history });
    this.context.store.messages.save({
      id,
      projectId: input.project.id,
      sessionId,
      role: "assistant",
      body,
      visibility: "internal",
      replyToMessageId: currentMessageId,
      createdAt: timestamp(),
    });
  }

  async reply(job: JobOfKind<"reply">): Promise<void> {
    if (job.payload.mode === "publish-user") {
      const { sessionId, findingId } = job.payload;
      const finding = findingId ? this.publishedFinding(findingId, sessionId) : undefined;
      const session = this.context.session(sessionId);
      const project = this.context.project(session.projectId);
      const snapshot = await this.context.importPullRequest(project.id, session.number);
      const message = requireEntity(this.context.store.messages.get(`message:${job.id}:user`));
      await this.publish(message, project, snapshot, finding);
      return;
    }

    const { sessionId, findingId, body, sourceMessageId } = job.payload;
    const finding = this.publishedFinding(findingId, sessionId);
    const input = await this.context.liveInput(sessionId);
    const messageId = `message:${job.id}:assistant`;
    let message = this.context.store.messages.get(messageId);
    if (!message) {
      const response = await this.context.agent.reply({ ...input, finding, message: body });
      const generated: Message = {
        id: messageId,
        projectId: input.project.id,
        sessionId,
        role: "assistant",
        body: response.body,
        visibility: "external",
        findingId,
        replyToMessageId: sourceMessageId,
        createdAt: timestamp(),
      };
      this.context.store.transaction(() => {
        this.context.store.messages.save(generated);
        if (response.verdict === "withdraw") {
          const current = requireEntity(this.context.store.findings.get(findingId));
          this.context.store.findings.save({
            ...current,
            status: "withdrawn",
            updatedAt: timestamp(),
          });
        }
      });
      message = generated;
    }
    await this.publish(message, input.project, input.snapshot, finding);
  }

  private publishedFinding(id: string, sessionId: string): PublishedFinding {
    const finding = this.context.store.findings.get(id);
    if (!finding || finding.sessionId !== sessionId || !finding.remoteId || !finding.publishedAt) {
      throw new DomainError("无法找到原始已发布评论");
    }
    return { ...finding, remoteId: finding.remoteId, publishedAt: finding.publishedAt };
  }

  private async publish(
    message: Message,
    project: Project,
    snapshot: PullRequestSnapshot,
    finding?: PublishedFinding,
  ): Promise<void> {
    const target = finding ? { replyToId: finding.remoteId, threadId: finding.remoteThreadId } : {};
    const publication = this.publications.ensurePublication(
      `publication:${message.id}`,
      {
        projectId: project.id,
        sessionId: message.sessionId,
        sourceMessageId: message.id,
        body: message.body,
      },
      snapshot,
      target,
    );
    const remote = await this.publications.deliver(publication, project);
    this.context.store.messages.save({
      ...message,
      remoteId: remote.id,
      remoteCommentKind: "agent-publication",
    });
  }
}
