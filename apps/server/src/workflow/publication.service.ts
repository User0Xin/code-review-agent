import {
  type Finding,
  type Project,
  type Publication,
  type PullRequestSnapshot,
  type RemoteComment,
  type ReviewRun,
} from "@review/contracts";
import { DomainError } from "../common/domain-error.js";
import { requireEntity } from "../common/require-entity.js";
import { timestamp } from "./utils.js";
import { type WorkflowContext } from "./workflow-context.js";

type PublicationIntent = Pick<Publication, "projectId" | "sessionId" | "body"> &
  (
    | { runId: string; findingId?: string; sourceMessageId?: never }
    | { sourceMessageId: string; runId?: never; findingId?: never }
  );

export class PublicationService {
  constructor(private readonly context: WorkflowContext) {}
  async publishRun(runId: string): Promise<void> {
    const run = this.context.run(runId);
    if (run.status === "superseded") {
      return;
    }
    if (run.status !== "completed" || !run.snapshot) {
      throw new DomainError("审查未完成，无法发布");
    }
    if (!(await this.context.isCurrent(run))) {
      return;
    }
    const project = run.projectSnapshot ?? this.context.project(run.projectId);
    const findings = this.context.store.findings
      .listBySession(run.sessionId, "open")
      .filter((f) => f.runId === run.id && f.status === "open");
    for (const candidate of findings) {
      const finding = await this.recoverFindingPublication(candidate, run, project);
      if (finding.publishedAt) {
        continue;
      }
      if (!(await this.context.isCurrent(run))) {
        return;
      }
      const recurrence = finding.remoteId ? "此问题在最新提交再次出现，请重新检查。\n\n" : "";
      const body = `${recurrence}**[${finding.severity}] ${finding.title}**\n\n${finding.body}\n\n依据：${finding.evidence}\n\n位置：\`${finding.path}${finding.line ? `:${finding.line}` : ""}\``;
      const target = finding.remoteId
        ? { replyToId: finding.remoteId, threadId: finding.remoteThreadId }
        : finding.line
          ? { path: finding.path, line: finding.line }
          : {};
      const publication = this.ensurePublication(
        `publication:${run.id}:${finding.id}`,
        {
          projectId: project.id,
          sessionId: run.sessionId,
          runId: run.id,
          findingId: finding.id,
          body,
        },
        run.snapshot,
        target,
      );
      const remote = await this.deliver(publication, project);
      this.context.store.findings.save({
        ...requireEntity(this.context.store.findings.get(finding.id)),
        remoteId: remote.id,
        remoteThreadId: remote.threadId ?? remote.id,
        publishedAt: timestamp(),
        updatedAt: timestamp(),
      });
    }
    if (!(await this.context.isCurrent(run))) {
      return;
    }
    const overview = this.ensurePublication(
      `publication:${run.id}:summary`,
      {
        projectId: project.id,
        sessionId: run.sessionId,
        runId: run.id,
        body: `### SparkShield 审查总结\n\n${run.summary}\n\n${run.coverage ?? ""}\n\n提交：\`${run.headSha}\` · 规范版本：${run.standardVersion}`,
      },
      run.snapshot,
    );
    await this.deliver(overview, project);
    const latest = this.context.run(run.id);
    this.context.store.runs.save({ ...latest, publishedAt: timestamp() });
  }

  async recoverFindingPublication(
    finding: Finding,
    run: ReviewRun,
    project: Project,
  ): Promise<Finding> {
    if (
      finding.publishedAt ||
      run.priorFindingsSnapshot?.some(
        (previous) => previous.id === finding.id && previous.status !== "open",
      )
    ) {
      return finding;
    }
    const previous = this.context.store.publications
      .listByFinding(finding.id)
      .filter((item) => item.findingId === finding.id && item.runId && item.runId !== run.id);
    if (!previous.length) {
      return finding;
    }
    const comments = await this.context.repository.getComments(
      project,
      this.context.store.vault.get(`repo:${project.id}`),
      requireEntity(run.snapshot).number,
    );
    for (const publication of previous.reverse()) {
      const remote = comments.find((comment) => comment.body.includes(publication.marker));
      if (!remote) {
        continue;
      }
      return this.context.store.transaction(() => {
        this.context.store.publications.save({
          ...publication,
          state: "published",
          remoteId: remote.id,
          remoteThreadId: remote.threadId ?? remote.id,
          updatedAt: timestamp(),
        });
        return this.context.store.findings.save({
          ...finding,
          remoteId: remote.id,
          remoteThreadId: remote.threadId ?? remote.id,
          publishedAt: timestamp(),
          updatedAt: timestamp(),
        });
      });
    }
    return finding;
  }

  ensurePublication(
    id: string,
    input: PublicationIntent,
    snapshot: PullRequestSnapshot,
    target: NonNullable<Publication["target"]> = {},
  ): Publication {
    const existing = this.context.store.publications.get(id);
    if (existing) {
      return existing;
    }
    const marker = `<!-- review-agent:${id} -->`;
    return this.context.store.publications.save({
      id,
      ...input,
      marker,
      snapshot,
      target,
      state: "pending",
      createdAt: timestamp(),
      updatedAt: timestamp(),
    });
  }
  async deliver(publication: Publication, project: Project): Promise<RemoteComment> {
    if (publication.state === "published" && publication.remoteId) {
      return {
        id: publication.remoteId,
        threadId: publication.remoteThreadId,
        body: publication.body,
        author: "SparkShield",
        isBot: true,
        createdAt: publication.createdAt,
      };
    }
    const snapshot = requireEntity(publication.snapshot);
    const token = this.context.store.vault.get(`repo:${project.id}`);
    const comments = await this.context.repository.getComments(project, token, snapshot.number);
    let remote = comments.find((comment) => comment.body.includes(publication.marker));
    if (!remote) {
      remote = await this.context.repository.publishComment(project, token, snapshot, {
        ...publication.target,
        body: `${publication.body}\n\n${publication.marker}`,
      });
    }
    this.context.store.publications.save({
      ...publication,
      state: "published",
      remoteId: remote.id,
      remoteThreadId: remote.threadId ?? remote.id,
      updatedAt: timestamp(),
    });
    return remote;
  }
}
