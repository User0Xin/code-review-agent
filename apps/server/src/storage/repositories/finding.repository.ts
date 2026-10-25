import type { Finding, FindingDraft } from "@review/contracts";
import { and, eq } from "drizzle-orm";
import { DomainError } from "../../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "../database.js";
import { findings, runFindings, sessions } from "../schema.js";

export class FindingRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): Finding[] {
    return this.db
      .select()
      .from(findings)
      .all()
      .map((row) => this.toFinding(row));
  }

  listBySession(sessionId: string, status?: Finding["status"]): Finding[] {
    const predicate = status
      ? and(eq(findings.sessionId, sessionId), eq(findings.currentStatus, status))
      : eq(findings.sessionId, sessionId);
    return this.db
      .select()
      .from(findings)
      .where(predicate)
      .all()
      .map((row) => this.toFinding(row));
  }

  listByProject(projectId: string): Finding[] {
    return this.db
      .select({ finding: findings })
      .from(findings)
      .innerJoin(sessions, eq(sessions.id, findings.sessionId))
      .where(eq(sessions.projectId, projectId))
      .all()
      .map((row) => this.toFinding(row.finding));
  }

  get(id: string): Finding | undefined {
    const row = this.db.select().from(findings).where(eq(findings.id, id)).get();
    return row ? this.toFinding(row) : undefined;
  }

  save(finding: Finding): Finding {
    return this.db.transaction(() => {
      const row = {
        id: finding.id,
        sessionId: finding.sessionId,
        fingerprint: finding.fingerprint,
        currentStatus: finding.status,
        firstSeenRunId: finding.runId,
        lastSeenRunId: finding.runId,
        remoteId: finding.remoteId ?? null,
        remoteThreadId: finding.remoteThreadId ?? null,
        publishedAt: finding.publishedAt ?? null,
        createdAt: finding.createdAt,
        updatedAt: finding.updatedAt,
      };
      const { firstSeenRunId: _firstSeenRunId, createdAt: _createdAt, ...updates } = row;
      this.db
        .insert(findings)
        .values(row)
        .onConflictDoUpdate({ target: findings.id, set: updates })
        .run();
      // Current lifecycle updates never rewrite an earlier assessment.
      if (!this.getAssessment(finding.runId, finding.id)) {
        this.recordAssessment(finding.runId, finding.id, finding.sessionId, "open", finding);
      }
      this.changed();
      return this.get(finding.id) ?? finding;
    });
  }

  getAssessment(runId: string, findingId: string) {
    return this.db
      .select()
      .from(runFindings)
      .where(and(eq(runFindings.runId, runId), eq(runFindings.findingId, findingId)))
      .get();
  }

  listAssessments(runId: string) {
    return this.db.select().from(runFindings).where(eq(runFindings.runId, runId)).all();
  }

  recordAssessment(
    runId: string,
    findingId: string,
    sessionId: string,
    verdict: Finding["status"],
    draft: FindingDraft,
  ): string {
    const id = `assessment:${runId}:${findingId}`;
    const row = {
      id,
      runId,
      findingId,
      sessionId,
      verdict,
      title: draft.title,
      body: draft.body,
      path: draft.path,
      line: draft.line,
      severity: draft.severity,
      category: draft.category,
      evidence: draft.evidence,
    };
    const existing = this.getAssessment(runId, findingId);
    if (existing) {
      if (Object.entries(row).some(([key, value]) => Reflect.get(existing, key) !== value)) {
        throw new DomainError("每轮问题结论不可修改", "conflict");
      }
      return existing.id;
    }
    this.db.insert(runFindings).values(row).run();
    return id;
  }

  recordResolution(runId: string, findingId: string): void {
    const finding = this.get(findingId);
    if (!finding) {
      throw new DomainError("待解决的问题不存在", "not-found");
    }
    this.recordAssessment(runId, findingId, finding.sessionId, "resolved", finding);
    this.db
      .update(findings)
      .set({ lastSeenRunId: runId, currentStatus: "resolved" })
      .where(eq(findings.id, findingId))
      .run();
  }

  private toFinding(row: typeof findings.$inferSelect): Finding {
    const assessment = this.getAssessment(row.lastSeenRunId, row.id);
    const session = this.db
      .select({ projectId: sessions.projectId })
      .from(sessions)
      .where(eq(sessions.id, row.sessionId))
      .get();
    if (!assessment || !session) {
      throw new DomainError("问题缺少所属会话或轮次结论");
    }
    return {
      id: row.id,
      projectId: session.projectId,
      sessionId: row.sessionId,
      runId: row.lastSeenRunId,
      fingerprint: row.fingerprint,
      status: row.currentStatus,
      title: assessment.title,
      body: assessment.body,
      path: assessment.path,
      line: assessment.line,
      severity: assessment.severity,
      category: assessment.category,
      evidence: assessment.evidence,
      remoteId: row.remoteId ?? undefined,
      remoteThreadId: row.remoteThreadId ?? undefined,
      publishedAt: row.publishedAt ?? undefined,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
