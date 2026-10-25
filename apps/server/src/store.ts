import Database from "better-sqlite3";
import { chmodSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { Subject } from "rxjs";
import { createDatabase } from "./storage/database.js";
import { EventRepository } from "./storage/event-repository.js";
import { JobQueue } from "./storage/job-queue.js";
import { initializeDatabase } from "./storage/initialize-database.js";
import {
  ModelRepository,
  ProjectRepository,
} from "./storage/repositories/configuration.repository.js";
import { ExperienceRepository } from "./storage/repositories/experience.repository.js";
import { ChatGptAccountRepository } from "./storage/repositories/chatgpt-account.repository.js";
import { FindingRepository } from "./storage/repositories/finding.repository.js";
import { MessageRepository } from "./storage/repositories/message.repository.js";
import { PublicationRepository } from "./storage/repositories/publication.repository.js";
import { ReviewRepository } from "./storage/repositories/review.repository.js";
import { SessionRepository } from "./storage/repositories/session.repository.js";
import { loadEncryptionKey, SecretVault } from "./storage/secret-vault.js";

/** Owns the connection and transactions; repositories express business persistence. */
export class Store {
  readonly changes = new Subject<{ version: number }>();
  readonly models: ModelRepository;
  readonly chatgptAccounts: ChatGptAccountRepository;
  readonly projects: ProjectRepository;
  readonly sessions: SessionRepository;
  readonly findings: FindingRepository;
  readonly runs: ReviewRepository;
  readonly messages: MessageRepository;
  readonly publications: PublicationRepository;
  readonly experiences: ExperienceRepository;
  readonly jobs: JobQueue;
  readonly events: EventRepository;
  readonly vault: SecretVault;
  private readonly connection: Database.Database;
  private version = 0;
  private transactionDepth = 0;
  private pendingChange = false;

  constructor(
    path = process.env.REVIEW_DATABASE_PATH || "./data/review.db",
    encryptionKey = process.env.REVIEW_ENCRYPTION_KEY,
  ) {
    if (path !== ":memory:") {
      mkdirSync(dirname(resolve(path)), { recursive: true, mode: 0o700 });
    }
    const key = loadEncryptionKey(path, encryptionKey);
    this.connection = new Database(path);
    if (path !== ":memory:") {
      chmodSync(path, 0o600);
    }
    this.connection.pragma("journal_mode = WAL");
    this.connection.pragma("foreign_keys = ON");
    this.connection.pragma("busy_timeout = 5000");
    this.connection.pragma("synchronous = FULL");
    const db = createDatabase(this.connection);
    const changed = () => this.notify();
    this.vault = new SecretVault(db, key);
    this.models = new ModelRepository(db, changed);
    this.chatgptAccounts = new ChatGptAccountRepository(db, changed);
    this.projects = new ProjectRepository(db, changed);
    this.sessions = new SessionRepository(db, changed);
    this.findings = new FindingRepository(db, changed);
    this.runs = new ReviewRepository(db, this.sessions, this.projects, this.findings, changed);
    this.messages = new MessageRepository(db, changed);
    this.publications = new PublicationRepository(
      db,
      this.sessions,
      this.findings,
      this.runs,
      changed,
    );
    this.experiences = new ExperienceRepository(db, changed);
    this.jobs = new JobQueue(db, this.connection, this.vault, changed);
    this.events = new EventRepository(db);
    try {
      // Verify saved credentials before initializing an existing database.
      if (
        this.connection
          .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='secrets'")
          .get()
      ) {
        try {
          this.vault.validate();
        } catch {
          throw new Error(
            "无法解密已保存凭据，请恢复此数据库对应的 master.key 或 REVIEW_ENCRYPTION_KEY",
          );
        }
      }
      initializeDatabase(this.connection);
    } catch (error) {
      this.connection.close();
      throw error;
    }
  }

  transaction<T>(operation: () => T): T {
    const previousPending = this.pendingChange;
    this.transactionDepth++;
    try {
      const result = this.connection.transaction(operation).immediate();
      this.transactionDepth--;
      if (this.transactionDepth === 0 && this.pendingChange) {
        this.pendingChange = false;
        this.notify();
      }
      return result;
    } catch (error) {
      this.transactionDepth--;
      this.pendingChange = previousPending;
      throw error;
    }
  }

  async backup(destination: string): Promise<void> {
    await this.connection.backup(destination);
  }

  close(): void {
    this.changes.complete();
    this.connection.close();
  }

  private notify(): void {
    if (this.transactionDepth > 0) {
      this.pendingChange = true;
      return;
    }
    this.changes.next({ version: ++this.version });
  }
}
