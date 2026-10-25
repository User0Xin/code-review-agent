import { eq } from "drizzle-orm";
import type { OnChange, ReviewDatabase } from "../database.js";
import { chatgptAccounts } from "../schema.js";

export interface ChatGptRegistration {
  id: string;
  email: string;
  subject: string;
  clientId: string;
  createdAt: string;
  updatedAt: string;
}

export class ChatGptAccountRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): ChatGptRegistration[] {
    return this.db.select().from(chatgptAccounts).all();
  }

  get(id: string): ChatGptRegistration | undefined {
    return this.db.select().from(chatgptAccounts).where(eq(chatgptAccounts.id, id)).get();
  }

  save(account: ChatGptRegistration): ChatGptRegistration {
    this.db
      .insert(chatgptAccounts)
      .values(account)
      .onConflictDoUpdate({ target: chatgptAccounts.id, set: account })
      .run();
    this.changed();
    return this.get(account.id) ?? account;
  }
}
