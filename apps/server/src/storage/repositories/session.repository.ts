import type { PullRequestSnapshot, Session } from "@review/contracts";
import { and, eq } from "drizzle-orm";
import { createHash } from "node:crypto";
import { DomainError } from "../../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "../database.js";
import { prSnapshots, sessions } from "../schema.js";

/** Includes description and coverage in identity, not only the commit SHA. */
function snapshotHash(snapshot: PullRequestSnapshot): string {
  const material: PullRequestSnapshot = {
    number: snapshot.number,
    title: snapshot.title,
    description: snapshot.description,
    url: snapshot.url,
    headSha: snapshot.headSha,
    baseSha: snapshot.baseSha,
    startSha: snapshot.startSha,
    branch: snapshot.branch,
    baseBranch: snapshot.baseBranch,
    state: snapshot.state,
    files: snapshot.files,
    truncated: snapshot.truncated,
  };
  return createHash("sha256").update(JSON.stringify(material)).digest("hex");
}

export class SessionRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): Session[] {
    return this.db
      .select()
      .from(sessions)
      .all()
      .map((row) => this.toSession(row));
  }

  listByProject(projectId: string): Session[] {
    return this.db
      .select()
      .from(sessions)
      .where(eq(sessions.projectId, projectId))
      .all()
      .map((row) => this.toSession(row));
  }

  get(id: string): Session | undefined {
    const row = this.db.select().from(sessions).where(eq(sessions.id, id)).get();
    return row ? this.toSession(row) : undefined;
  }

  findByPullRequest(projectId: string, number: number): Session | undefined {
    const row = this.db
      .select()
      .from(sessions)
      .where(and(eq(sessions.projectId, projectId), eq(sessions.number, number)))
      .get();
    return row ? this.toSession(row) : undefined;
  }

  save(session: Session): Session {
    return this.db.transaction(() => {
      const row = {
        id: session.id,
        projectId: session.projectId,
        number: session.number,
        title: session.title,
        url: session.url,
        state: session.state,
        ciStatus: session.ciStatus,
        lastReviewAt: session.lastReviewAt,
        createdAt: session.createdAt,
        updatedAt: session.updatedAt,
      };
      this.db
        .insert(sessions)
        .values(row)
        .onConflictDoUpdate({ target: sessions.id, set: row })
        .run();
      const snapshotId = this.saveSnapshot(session.id, session);
      this.db
        .update(sessions)
        .set({ currentSnapshotId: snapshotId })
        .where(eq(sessions.id, session.id))
        .run();
      this.changed();
      return session;
    });
  }

  saveSnapshot(sessionId: string, snapshot: PullRequestSnapshot): string {
    const hash = snapshotHash(snapshot);
    const id = `snapshot:${sessionId}:${hash}`;
    this.db
      .insert(prSnapshots)
      .values({
        id,
        sessionId,
        headSha: snapshot.headSha,
        baseSha: snapshot.baseSha,
        startSha: snapshot.startSha,
        title: snapshot.title,
        description: snapshot.description,
        url: snapshot.url,
        branch: snapshot.branch,
        baseBranch: snapshot.baseBranch,
        state: snapshot.state,
        files: snapshot.files,
        coverage: { truncated: snapshot.truncated },
        contentHash: hash,
        fetchedAt: new Date().toISOString(),
      })
      .onConflictDoNothing({ target: [prSnapshots.sessionId, prSnapshots.contentHash] })
      .run();
    return id;
  }

  getSnapshot(id: string): PullRequestSnapshot {
    const row = this.db.select().from(prSnapshots).where(eq(prSnapshots.id, id)).get();
    if (!row) {
      throw new DomainError("PR 快照不存在", "not-found");
    }
    const session = this.db
      .select({ number: sessions.number })
      .from(sessions)
      .where(eq(sessions.id, row.sessionId))
      .get();
    if (!session) {
      throw new DomainError("PR 会话不存在", "not-found");
    }
    return {
      number: session.number,
      title: row.title,
      description: row.description,
      url: row.url,
      headSha: row.headSha,
      baseSha: row.baseSha,
      startSha: row.startSha ?? undefined,
      branch: row.branch,
      baseBranch: row.baseBranch,
      state: row.state,
      files: row.files,
      truncated: row.coverage.truncated,
    };
  }

  private toSession(row: typeof sessions.$inferSelect): Session {
    if (!row.currentSnapshotId) {
      throw new DomainError("PR 会话的当前快照缺失");
    }
    return {
      ...this.getSnapshot(row.currentSnapshotId),
      id: row.id,
      projectId: row.projectId,
      title: row.title,
      url: row.url,
      state: row.state,
      ciStatus: row.ciStatus,
      lastReviewAt: row.lastReviewAt ?? undefined,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
