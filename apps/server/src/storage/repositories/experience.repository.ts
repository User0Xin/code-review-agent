import type { Experience } from "@review/contracts";
import { and, eq } from "drizzle-orm";
import type { OnChange, ReviewDatabase } from "../database.js";
import { experiences, experienceSources } from "../schema.js";

export class ExperienceRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): Experience[] {
    return this.db
      .select()
      .from(experiences)
      .all()
      .map((row) => this.toExperience(row));
  }

  listByProject(projectId: string): Experience[] {
    return this.db
      .select()
      .from(experiences)
      .where(eq(experiences.projectId, projectId))
      .all()
      .map((row) => this.toExperience(row));
  }

  listAccepted(projectId: string): Experience[] {
    return this.db
      .select()
      .from(experiences)
      .where(
        and(
          eq(experiences.projectId, projectId),
          eq(experiences.status, "accepted"),
          eq(experiences.kind, "experience"),
        ),
      )
      .all()
      .map((row) => this.toExperience(row));
  }

  get(id: string): Experience | undefined {
    const row = this.db.select().from(experiences).where(eq(experiences.id, id)).get();
    return row ? this.toExperience(row) : undefined;
  }

  save(
    experience: Experience,
    source?: { evidence: string; jobId?: string },
    acceptedStandardVersionId?: string,
  ): Experience {
    return this.db.transaction(() => {
      const row = {
        id: experience.id,
        projectId: experience.projectId,
        kind: experience.kind,
        body: experience.body,
        status: experience.status,
        generatedByJobId: source?.jobId,
        decidedAt: experience.status !== "draft" ? experience.updatedAt : undefined,
        acceptedStandardVersionId,
        createdAt: experience.createdAt,
        updatedAt: experience.updatedAt,
      };
      this.db
        .insert(experiences)
        .values(row)
        .onConflictDoUpdate({
          target: experiences.id,
          set: {
            status: row.status,
            decidedAt: row.decidedAt,
            acceptedStandardVersionId,
            updatedAt: row.updatedAt,
          },
        })
        .run();
      for (const sessionId of experience.sourceSessionIds) {
        this.db
          .insert(experienceSources)
          .values({
            id: `source:${experience.id}:${sessionId}`,
            experienceId: experience.id,
            sessionId,
            projectId: experience.projectId,
            evidence: {
              evidence: source?.evidence ?? "",
              provenance: source ? "captured" : "unavailable",
            },
          })
          .onConflictDoNothing({
            target: [experienceSources.experienceId, experienceSources.sessionId],
          })
          .run();
      }
      this.changed();
      return this.get(experience.id) ?? experience;
    });
  }

  private toExperience(row: typeof experiences.$inferSelect): Experience {
    const sources = this.db
      .select({ sessionId: experienceSources.sessionId })
      .from(experienceSources)
      .where(eq(experienceSources.experienceId, row.id))
      .all();
    return {
      id: row.id,
      projectId: row.projectId,
      kind: row.kind,
      body: row.body,
      status: row.status,
      sourceSessionIds: sources.map((source) => source.sessionId),
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
