import { jobSchema, type EnqueueArguments, type Job } from "@review/contracts";
import type Database from "better-sqlite3";
import { desc, eq } from "drizzle-orm";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { DomainError } from "../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "./database.js";
import { jobs, reviewRuns, sessions } from "./schema.js";
import type { SecretVault } from "./secret-vault.js";

const claimedRowSchema = z.object({
  id: z.string(),
  kind: z.string(),
  key: z.string(),
  payload: z.string(),
  status: z.string(),
  attempts: z.number(),
  max_attempts: z.number(),
  available_at: z.number(),
  locked_at: z.number().nullable(),
  error: z.string().nullable(),
  created_at: z.number(),
});

export class JobQueue {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly connection: Database.Database,
    private readonly vault: SecretVault,
    private readonly changed: OnChange,
  ) {}

  enqueue(...[kind, payload, key]: EnqueueArguments): Job {
    const now = Date.now();
    const job = jobSchema.parse({
      id: randomUUID(),
      kind,
      payload,
      key,
      status: "queued",
      attempts: 0,
      maxAttempts: 3,
      availableAt: now,
      lockedAt: null,
      error: null,
      createdAt: now,
    });
    const runId = job.kind === "publish" ? job.payload.runId : undefined;
    const run = runId
      ? this.db.select().from(reviewRuns).where(eq(reviewRuns.id, runId)).get()
      : undefined;
    const sessionId = "sessionId" in job.payload ? job.payload.sessionId : run?.sessionId;
    const session = sessionId
      ? this.db.select().from(sessions).where(eq(sessions.id, sessionId)).get()
      : undefined;
    const projectId = "projectId" in job.payload ? job.payload.projectId : session?.projectId;
    this.db
      .insert(jobs)
      .values({ ...job, runId, sessionId, projectId })
      .onConflictDoNothing({ target: jobs.key })
      .run();
    const saved = this.db.select().from(jobs).where(eq(jobs.key, key)).get();
    if (!saved) {
      throw new Error("任务入队后未找到记录");
    }
    this.changed();
    return jobSchema.parse(saved);
  }

  claim(): Job | undefined {
    return this.connection
      .transaction(() => {
        const value: unknown = this.connection
          .prepare(
            `UPDATE jobs SET status='running', attempts=attempts+1, locked_at=? WHERE id=(SELECT id FROM jobs WHERE status='queued' AND available_at<=? ORDER BY created_at,id LIMIT 1) RETURNING *`,
          )
          .get(Date.now(), Date.now());
        if (!value) {
          return undefined;
        }
        const row = claimedRowSchema.parse(value);
        const payload: unknown = JSON.parse(row.payload);
        const job = jobSchema.parse({
          id: row.id,
          kind: row.kind,
          key: row.key,
          payload,
          status: row.status,
          attempts: row.attempts,
          maxAttempts: row.max_attempts,
          availableAt: row.available_at,
          lockedAt: row.locked_at,
          error: row.error,
          createdAt: row.created_at,
        });
        this.changed();
        return job;
      })
      .immediate();
  }

  complete(id: string): void {
    this.db
      .update(jobs)
      .set({ status: "completed", lockedAt: null, error: null })
      .where(eq(jobs.id, id))
      .run();
    this.changed();
  }

  fail(id: string, error: string | Error): void {
    const job = this.get(id);
    if (!job) {
      return;
    }
    this.db
      .update(jobs)
      .set({
        status: job.attempts >= job.maxAttempts ? "failed" : "queued",
        lockedAt: null,
        error: this.vault.redact(error instanceof Error ? error.message : error),
        availableAt: Date.now() + Math.min(60_000, 1000 * 2 ** job.attempts),
      })
      .where(eq(jobs.id, id))
      .run();
    this.changed();
  }

  recover(): void {
    this.connection
      .transaction(() => {
        const now = Date.now();
        this.connection
          .prepare(
            `UPDATE review_runs SET execution_status='failed',completed_at=?,error='服务重启，任务重试次数已耗尽'
           WHERE execution_status='running' AND superseded_at IS NULL
           AND id IN (SELECT 'run:' || id FROM jobs WHERE kind='review' AND status='running' AND attempts>=max_attempts)`,
          )
          .run(new Date(now).toISOString());
        this.connection
          .prepare(
            "UPDATE jobs SET status=CASE WHEN attempts>=max_attempts THEN 'failed' ELSE 'queued' END,locked_at=NULL,available_at=?,error=CASE WHEN attempts>=max_attempts THEN '服务重启，任务重试次数已耗尽' ELSE '服务重启，任务已恢复' END WHERE status='running'",
          )
          .run(now);
      })
      .immediate();
    this.changed();
  }

  get(id: string): Job | undefined {
    const row = this.db.select().from(jobs).where(eq(jobs.id, id)).get();
    return row ? jobSchema.parse(row) : undefined;
  }

  list(limit?: number): Job[] {
    const query = this.db.select().from(jobs).orderBy(desc(jobs.createdAt), desc(jobs.id));
    return (limit === undefined ? query.all() : query.limit(limit).all()).map((row) =>
      jobSchema.parse(row),
    );
  }

  retry(id: string): Job {
    const job = this.get(id);
    if (!job) {
      throw new DomainError("任务不存在", "not-found");
    }
    if (job.status !== "failed") {
      throw new DomainError("只能重试失败任务");
    }
    this.db
      .update(jobs)
      .set({ status: "queued", attempts: 0, lockedAt: null, availableAt: Date.now(), error: null })
      .where(eq(jobs.id, id))
      .run();
    this.changed();
    return { ...job, status: "queued", attempts: 0, lockedAt: null, error: null };
  }
}
