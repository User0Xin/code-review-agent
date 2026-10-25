import type { Message } from "@review/contracts";
import { asc, eq, sql } from "drizzle-orm";
import type { OnChange, ReviewDatabase } from "../database.js";
import { messages } from "../schema.js";

export class MessageRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): Message[] {
    return this.db
      .select()
      .from(messages)
      .orderBy(asc(messages.createdAt), sql`${messages}.rowid`)
      .all()
      .map((row) => this.toMessage(row));
  }

  listBySession(sessionId: string): Message[] {
    return this.db
      .select()
      .from(messages)
      .where(eq(messages.sessionId, sessionId))
      .orderBy(asc(messages.createdAt), sql`${messages}.rowid`)
      .all()
      .map((row) => this.toMessage(row));
  }

  listByProject(projectId: string): Message[] {
    return this.db
      .select()
      .from(messages)
      .where(eq(messages.projectId, projectId))
      .orderBy(asc(messages.createdAt), sql`${messages}.rowid`)
      .all()
      .map((row) => this.toMessage(row));
  }

  get(id: string): Message | undefined {
    const row = this.db.select().from(messages).where(eq(messages.id, id)).get();
    return row ? this.toMessage(row) : undefined;
  }

  save(message: Message): Message {
    const row = {
      id: message.id,
      projectId: message.projectId,
      sessionId: message.sessionId,
      findingId: message.findingId,
      role: message.role,
      visibility: message.visibility,
      body: message.body,
      remoteId: message.remoteId ?? null,
      remoteCommentKind: message.remoteId ? (message.remoteCommentKind ?? "comment") : null,
      replyToMessageId: message.replyToMessageId,
      createdAt: message.createdAt,
    };
    this.db
      .insert(messages)
      .values(row)
      .onConflictDoUpdate({
        target: messages.id,
        set: { remoteId: row.remoteId, remoteCommentKind: row.remoteCommentKind },
      })
      .run();
    this.changed();
    return this.get(message.id) ?? message;
  }

  private toMessage(row: typeof messages.$inferSelect): Message {
    return {
      id: row.id,
      projectId: row.projectId,
      sessionId: row.sessionId,
      findingId: row.findingId ?? undefined,
      role: row.role,
      visibility: row.visibility,
      body: row.body,
      remoteId: row.remoteId ?? undefined,
      remoteCommentKind: row.remoteCommentKind ?? undefined,
      replyToMessageId: row.replyToMessageId ?? undefined,
      createdAt: row.createdAt,
    };
  }
}
