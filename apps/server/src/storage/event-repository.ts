import type { NormalizedEvent, Provider } from "@review/contracts";
import { eq } from "drizzle-orm";
import type { ReviewDatabase } from "./database.js";
import { webhookEvents } from "./schema.js";

export class EventRepository {
  constructor(private readonly db: ReviewDatabase) {}

  has(id: string): boolean {
    return (
      this.db
        .select({ id: webhookEvents.id })
        .from(webhookEvents)
        .where(eq(webhookEvents.id, id))
        .get() !== undefined
    );
  }

  record(id: string, projectId: string, provider: Provider, event: NormalizedEvent): void {
    this.db
      .insert(webhookEvents)
      .values({ id, projectId, provider, deliveryId: event.id, event, receivedAt: Date.now() })
      .run();
  }
}
