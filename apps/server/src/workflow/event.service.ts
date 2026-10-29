import { type NormalizedEvent } from "@review/contracts";
import { normalized, ownMarker, timestamp } from "./utils.js";
import { type WorkflowContext } from "./workflow-context.js";

export class EventService {
  constructor(private readonly context: WorkflowContext) {}
  async acceptEvent(projectId: string, event: NormalizedEvent): Promise<void> {
    const project = this.context.project(projectId);
    if (normalized(event.repository) !== normalized(project.repository)) {
      return;
    }
    if (event.type === "ci") {
      if (!event.headSha) {
        return;
      }
      this.context.store.transaction(() => {
        for (const session of this.context.store.sessions.listByProject(projectId)) {
          if (
            session.projectId === projectId &&
            session.headSha === event.headSha &&
            (!event.number || event.number === session.number)
          ) {
            this.context.store.sessions.save({
              ...session,
              ciStatus: event.state ?? "unknown",
              updatedAt: timestamp(),
            });
          }
        }
      });
      return;
    }
    if (!event.number) {
      return;
    }
    if (event.type === "reply") {
      if (!event.body?.trim() || !event.commentId || event.isBot || ownMarker(event.body)) {
        return;
      }
      const session = this.context.store.sessions.findByPullRequest(projectId, event.number);
      if (!session) {
        return;
      }
      let threadId = event.threadId;
      if (!threadId && project.provider === "gitlab") {
        const comments = await this.context.repository.getComments(
          project,
          this.context.store.vault.get(`repo:${project.id}`),
          session.number,
        );
        threadId = comments.find((comment) => comment.id === event.commentId)?.threadId;
      }
      const finding = this.context.store.findings
        .listBySession(session.id)
        .find(
          (f) =>
            f.sessionId === session.id &&
            f.publishedAt &&
            ((threadId && f.remoteThreadId === threadId) || (threadId && f.remoteId === threadId)),
        );
      if (!finding) {
        return;
      }
      const body = event.body;
      const commentId = event.commentId;
      this.context.store.transaction(() => {
        const id = `remote:${projectId}:${event.commentId}`;
        if (this.context.store.messages.get(id)) {
          return;
        }
        this.context.store.messages.save({
          id,
          projectId,
          sessionId: session.id,
          role: "user",
          visibility: "external",
          findingId: finding.id,
          remoteId: commentId,
          remoteCommentKind: `${project.provider}:${commentId.split(":")[0]}`,
          body,
          createdAt: timestamp(),
        });
        this.context.store.jobs.enqueue(
          "reply",
          {
            sessionId: session.id,
            findingId: finding.id,
            body,
            sourceMessageId: id,
            mode: "feedback",
          },
          `reply:${projectId}:${event.commentId}`,
        );
      });
      return;
    }
    const session = await this.context.importPullRequest(projectId, event.number);
    if (event.type === "pr" && session.state === "open" && project.autoReview) {
      this.context.store.jobs.enqueue(
        "review",
        { sessionId: session.id, trigger: "webhook" },
        `review:event:${projectId}:${event.id}`,
      );
    }
  }
}
