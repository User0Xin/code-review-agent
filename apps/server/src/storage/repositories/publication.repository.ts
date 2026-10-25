import type { Publication } from "@review/contracts";
import { eq } from "drizzle-orm";
import { DomainError } from "../../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "../database.js";
import { publications, runFindings } from "../schema.js";
import type { FindingRepository } from "./finding.repository.js";
import type { ReviewRepository } from "./review.repository.js";
import type { SessionRepository } from "./session.repository.js";

export class PublicationRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly sessions: SessionRepository,
    private readonly findings: FindingRepository,
    private readonly runs: ReviewRepository,
    private readonly changed: OnChange,
  ) {}

  list(): Publication[] {
    return this.db
      .select()
      .from(publications)
      .all()
      .map((row) => this.toPublication(row));
  }

  listByFinding(findingId: string): Publication[] {
    return this.db
      .select({ publication: publications })
      .from(publications)
      .innerJoin(runFindings, eq(publications.sourceRunFindingId, runFindings.id))
      .where(eq(runFindings.findingId, findingId))
      .all()
      .map((row) => this.toPublication(row.publication));
  }

  get(id: string): Publication | undefined {
    const row = this.db.select().from(publications).where(eq(publications.id, id)).get();
    return row ? this.toPublication(row) : undefined;
  }

  save(publication: Publication): Publication {
    const existing = this.get(publication.id);
    if (existing) {
      if (existing.state === "published" && publication.state !== "published") {
        throw new DomainError("已完成的发布记录不能恢复为待发送", "conflict");
      }
      this.db
        .update(publications)
        .set({
          state: publication.state,
          remoteId: publication.remoteId ?? null,
          remoteThreadId: publication.remoteThreadId ?? null,
          publishedAt: publication.state === "published" ? publication.updatedAt : null,
          updatedAt: publication.updatedAt,
        })
        .where(eq(publications.id, publication.id))
        .run();
    } else {
      const run = publication.runId ? this.runs.get(publication.runId) : undefined;
      const assessment =
        publication.runId && publication.findingId
          ? this.findings.getAssessment(publication.runId, publication.findingId)
          : undefined;
      const snapshot = publication.snapshot ?? run?.snapshot;
      if (!snapshot) {
        throw new DomainError("发布意图必须冻结代码快照");
      }
      if (publication.findingId && !assessment && !publication.sourceMessageId) {
        throw new DomainError("发布的问题缺少本轮结论");
      }
      const kind = publication.sourceMessageId ? "message" : assessment ? "finding" : "summary";
      const snapshotId = this.sessions.saveSnapshot(publication.sessionId, snapshot);
      this.db
        .insert(publications)
        .values({
          id: publication.id,
          projectId: publication.projectId,
          sessionId: publication.sessionId,
          kind,
          sourceRunId: kind === "summary" ? publication.runId : null,
          sourceRunFindingId: kind === "finding" ? assessment?.id : null,
          sourceMessageId: publication.sourceMessageId,
          idempotencyKey: publication.id,
          marker: publication.marker,
          body: publication.body,
          target: publication.target ?? {},
          snapshotId,
          state: publication.state,
          remoteId: publication.remoteId,
          remoteThreadId: publication.remoteThreadId,
          publishedAt: publication.state === "published" ? publication.updatedAt : null,
          createdAt: publication.createdAt,
          updatedAt: publication.updatedAt,
        })
        .run();
    }
    this.changed();
    return this.get(publication.id) ?? publication;
  }

  private toPublication(row: typeof publications.$inferSelect): Publication {
    const assessment = row.sourceRunFindingId
      ? this.db.select().from(runFindings).where(eq(runFindings.id, row.sourceRunFindingId)).get()
      : undefined;
    return {
      id: row.id,
      projectId: row.projectId,
      sessionId: row.sessionId,
      runId: row.sourceRunId ?? assessment?.runId,
      findingId: assessment?.findingId,
      sourceMessageId: row.sourceMessageId ?? undefined,
      body: row.body,
      marker: row.marker,
      state: row.state,
      remoteId: row.remoteId ?? undefined,
      remoteThreadId: row.remoteThreadId ?? undefined,
      target: row.target,
      snapshot: this.sessions.getSnapshot(row.snapshotId),
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
