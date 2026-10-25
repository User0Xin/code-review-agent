import type { Finding, JobOfKind, ReviewResult, ReviewRun } from "@review/contracts";
import { randomUUID } from "node:crypto";
import { fingerprint } from "../common/finding-fingerprint.js";
import { reviewScope, validateReviewResult } from "./review-validation.js";
import { clone, errorText, timestamp } from "./utils.js";
import type { WorkflowContext } from "./workflow-context.js";

export class ReviewService {
  constructor(private readonly context: WorkflowContext) {}

  async review(job: JobOfKind<"review">): Promise<void> {
    const id = `run:${job.id}`;
    try {
      const run = await this.loadRun(job);
      if (!run || !(await this.context.isCurrent(run))) {
        return;
      }
      const input = this.context.inputForRun(run);
      const scoped = reviewScope(input);
      const output = await this.context.agent.review(scoped);
      if (!(await this.context.isCurrent(run))) {
        return;
      }
      const { result, coverage } = validateReviewResult(input, scoped, output);
      this.completeReview(this.context.run(id), result, coverage);
    } catch (error) {
      const current = this.context.store.runs.get(id);
      if (current && current.status !== "superseded") {
        this.context.store.runs.save({
          ...current,
          status: "failed",
          error: this.context.store.vault.redact(errorText(error)),
        });
      }
      throw error;
    }
  }

  private async loadRun(job: JobOfKind<"review">): Promise<ReviewRun | undefined> {
    const id = `run:${job.id}`;
    const existing = this.context.store.runs.get(id);
    if (existing?.status === "completed" || existing?.status === "superseded") {
      return undefined;
    }
    if (existing) {
      return this.context.store.runs.save({ ...existing, status: "running", error: undefined });
    }
    const input = await this.context.liveInput(job.payload.sessionId);
    if (input.snapshot.state !== "open") {
      return undefined;
    }
    return this.context.store.runs.save({
      id,
      sessionId: job.payload.sessionId,
      projectId: input.project.id,
      headSha: input.snapshot.headSha,
      status: "running",
      trigger: job.payload.trigger ?? "manual",
      standardVersion: input.project.standardVersion,
      standardSnapshot: input.project.standard,
      modelSnapshot: {
        id: input.model.id,
        name: input.model.name,
        baseUrl: input.model.baseUrl,
        model: input.model.model,
        authMode: input.model.authMode ?? "api-key",
        chatgptAccountId: input.model.chatgptAccountId,
      },
      experienceIds: input.experiences.map((experience) => experience.id),
      experienceSnapshot: clone(input.experiences),
      ignorePatternsSnapshot: clone(input.project.ignorePatterns),
      priorFindingIds: input.priorFindings.map((finding) => finding.id),
      priorFindingsSnapshot: clone(input.priorFindings),
      projectSnapshot: clone(input.project),
      summary: "",
      createdAt: timestamp(),
      snapshot: clone(input.snapshot),
    });
  }

  private completeReview(run: ReviewRun, result: ReviewResult, coverage: string): void {
    if (run.status === "superseded") {
      return;
    }
    this.context.store.transaction(() => {
      const previous = new Map(
        this.context.store.findings
          .listBySession(run.sessionId)
          .map((finding) => [finding.fingerprint, finding]),
      );
      for (const draft of result.findings) {
        const key = fingerprint(draft);
        const existing = previous.get(key);
        const finding: Finding = {
          ...existing,
          ...draft,
          id: existing?.id ?? randomUUID(),
          projectId: run.projectId,
          sessionId: run.sessionId,
          runId: run.id,
          fingerprint: key,
          status: "open",
          publishedAt: existing && existing.status !== "open" ? undefined : existing?.publishedAt,
          createdAt: existing?.createdAt ?? timestamp(),
          updatedAt: timestamp(),
        };
        this.context.store.findings.save(finding);
      }
      this.context.store.runs.save({
        ...run,
        status: "completed",
        summary: result.summary,
        resultSnapshot: result,
        coverage,
        completedAt: timestamp(),
        error: undefined,
      });
      const session = this.context.session(run.sessionId);
      this.context.store.sessions.save({
        ...session,
        lastReviewAt: timestamp(),
        updatedAt: timestamp(),
      });
      if (run.projectSnapshot?.autoPublish) {
        this.context.store.jobs.enqueue("publish", { runId: run.id }, `publish:${run.id}`);
      }
    });
  }
}
