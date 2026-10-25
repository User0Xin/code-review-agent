import type { ReviewRun } from "@review/contracts";
import { asc, eq } from "drizzle-orm";
import { DomainError } from "../../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "../database.js";
import { reviewRuns, standardVersions } from "../schema.js";
import type { ProjectRepository } from "./configuration.repository.js";
import type { FindingRepository } from "./finding.repository.js";
import type { SessionRepository } from "./session.repository.js";

export class ReviewRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly sessions: SessionRepository,
    private readonly projects: ProjectRepository,
    private readonly findings: FindingRepository,
    private readonly changed: OnChange,
  ) {}

  list(): ReviewRun[] {
    return this.db
      .select()
      .from(reviewRuns)
      .all()
      .map((row) => this.toRun(row));
  }

  listBySession(sessionId: string): ReviewRun[] {
    return this.db
      .select()
      .from(reviewRuns)
      .where(eq(reviewRuns.sessionId, sessionId))
      .orderBy(asc(reviewRuns.createdAt), asc(reviewRuns.id))
      .all()
      .map((row) => this.toRun(row));
  }

  get(id: string): ReviewRun | undefined {
    const row = this.db.select().from(reviewRuns).where(eq(reviewRuns.id, id)).get();
    return row ? this.toRun(row) : undefined;
  }

  save(run: ReviewRun): ReviewRun {
    return this.db.transaction(() => {
      const existing = this.db.select().from(reviewRuns).where(eq(reviewRuns.id, run.id)).get();
      if (
        existing?.executionStatus === "completed" &&
        run.status !== "completed" &&
        run.status !== "superseded"
      ) {
        throw new DomainError("已完成的审查不能重新进入执行状态", "conflict");
      }
      const previousExecution =
        existing?.executionStatus ??
        run.executionStatus ??
        (run.resultSnapshot ? "completed" : run.error ? "failed" : "queued");
      const executionStatus =
        run.status === "superseded"
          ? previousExecution === "queued" || previousExecution === "running"
            ? "cancelled"
            : previousExecution
          : run.status;
      const supersededAt =
        existing?.supersededAt ??
        run.supersededAt ??
        (run.status === "superseded" ? new Date().toISOString() : null);
      if (!existing) {
        if (!run.snapshot) {
          throw new DomainError("新审查轮次必须包含冻结快照");
        }
        const snapshotId = this.sessions.saveSnapshot(run.sessionId, run.snapshot);
        const standardVersionId = this.projects.ensureStandardVersion(
          run.projectId,
          run.standardVersion,
          run.standardSnapshot,
          run.createdAt,
        );
        this.db
          .insert(reviewRuns)
          .values({
            id: run.id,
            projectId: run.projectId,
            sessionId: run.sessionId,
            snapshotId,
            standardVersionId,
            modelId: run.modelSnapshot.id,
            executionStatus,
            supersededAt,
            trigger: run.trigger,
            summary: run.summary,
            coverage: { description: run.coverage },
            inputContext: {
              project: run.projectSnapshot,
              model: run.modelSnapshot,
              experiences: run.experienceSnapshot ?? [],
              priorFindings: run.priorFindingsSnapshot ?? [],
              ignorePatterns: run.ignorePatternsSnapshot ?? [],
            },
            error: run.error,
            createdAt: run.createdAt,
            completedAt: run.completedAt,
            publishedAt: run.publishedAt,
          })
          .run();
      } else {
        this.db
          .update(reviewRuns)
          .set({
            executionStatus,
            supersededAt,
            summary: run.summary,
            coverage: { description: run.coverage },
            error: run.error ?? null,
            completedAt: run.completedAt ?? null,
            publishedAt: run.publishedAt ?? null,
          })
          .where(eq(reviewRuns.id, run.id))
          .run();
      }
      if (executionStatus === "completed" && existing?.executionStatus !== "completed") {
        for (const findingId of run.resultSnapshot?.resolvedFindingIds ?? []) {
          this.findings.recordResolution(run.id, findingId);
        }
      }
      this.changed();
      return this.get(run.id) ?? run;
    });
  }

  private toRun(row: typeof reviewRuns.$inferSelect): ReviewRun {
    const standard = this.db
      .select()
      .from(standardVersions)
      .where(eq(standardVersions.id, row.standardVersionId))
      .get();
    if (!standard) {
      throw new DomainError("审查引用的规范版本不存在");
    }
    const snapshot = this.sessions.getSnapshot(row.snapshotId);
    const assessments = this.findings.listAssessments(row.id);
    const context = row.inputContext;
    return {
      id: row.id,
      projectId: row.projectId,
      sessionId: row.sessionId,
      headSha: snapshot.headSha,
      status:
        row.supersededAt || row.executionStatus === "cancelled"
          ? "superseded"
          : row.executionStatus,
      executionStatus: row.executionStatus,
      supersededAt: row.supersededAt ?? undefined,
      trigger: row.trigger,
      standardVersion: standard.version,
      standardSnapshot: standard.body,
      modelSnapshot: context.model,
      experienceIds: context.experiences.map((experience) => experience.id),
      experienceSnapshot: context.experiences,
      ignorePatternsSnapshot: context.ignorePatterns,
      priorFindingIds: context.priorFindings.map((finding) => finding.id),
      priorFindingsSnapshot: context.priorFindings,
      projectSnapshot: context.project,
      summary: row.summary,
      coverage: row.coverage.description,
      error: row.error ?? undefined,
      createdAt: row.createdAt,
      completedAt: row.completedAt ?? undefined,
      publishedAt: row.publishedAt ?? undefined,
      snapshot,
      resultSnapshot:
        row.executionStatus === "completed"
          ? {
              summary: row.summary,
              findings: assessments
                .filter((assessment) => assessment.verdict === "open")
                .map(({ title, body, path, line, severity, category, evidence }) => ({
                  title,
                  body,
                  path,
                  line,
                  severity,
                  category,
                  evidence,
                })),
              resolvedFindingIds: assessments
                .filter((assessment) => assessment.verdict === "resolved")
                .map((assessment) => assessment.findingId),
            }
          : undefined,
    };
  }
}
