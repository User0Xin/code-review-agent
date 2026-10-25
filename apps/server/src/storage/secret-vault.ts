import { eq } from "drizzle-orm";
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import type { ReviewDatabase } from "./database.js";
import { modelConnections, projects, secrets } from "./schema.js";

export function loadEncryptionKey(path: string, configured?: string): Buffer {
  if (configured) {
    const key = Buffer.from(configured, "base64");
    if (key.length !== 32) {
      throw new Error("REVIEW_ENCRYPTION_KEY 必须是 32 字节的 base64 密钥");
    }
    return key;
  }
  if (path === ":memory:") {
    return randomBytes(32);
  }
  const filename = resolve(dirname(path), "master.key");
  if (!existsSync(filename)) {
    writeFileSync(filename, randomBytes(32), { mode: 0o600, flag: "wx" });
  }
  const key = readFileSync(filename);
  if (key.length !== 32) {
    throw new Error("本地 master.key 无效");
  }
  return key;
}

export class SecretVault {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly key: Buffer,
  ) {}

  validate(): void {
    for (const row of this.db.select({ id: secrets.id }).from(secrets).all()) {
      this.get(row.id);
    }
  }

  set(id: string, value: string): void {
    if (!value) {
      this.db.delete(secrets).where(eq(secrets.id, id)).run();
      return;
    }
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.key, iv);
    const ciphertext = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
    const encrypted = Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64");
    this.db
      .insert(secrets)
      .values({ id, value: encrypted })
      .onConflictDoUpdate({ target: secrets.id, set: { value: encrypted } })
      .run();
    const separator = id.indexOf(":");
    const kind = id.slice(0, separator);
    const entityId = id.slice(separator + 1);
    if (kind === "model") {
      this.db
        .update(modelConnections)
        .set({ apiKeySecretId: id })
        .where(eq(modelConnections.id, entityId))
        .run();
    }
    if (kind === "repo") {
      this.db.update(projects).set({ tokenSecretId: id }).where(eq(projects.id, entityId)).run();
    }
    if (kind === "webhook") {
      this.db.update(projects).set({ webhookSecretId: id }).where(eq(projects.id, entityId)).run();
    }
  }

  get(id: string): string {
    const row = this.db.select().from(secrets).where(eq(secrets.id, id)).get();
    if (!row) {
      return "";
    }
    const encrypted = Buffer.from(row.value, "base64");
    const decipher = createDecipheriv("aes-256-gcm", this.key, encrypted.subarray(0, 12));
    decipher.setAuthTag(encrypted.subarray(12, 28));
    return Buffer.concat([decipher.update(encrypted.subarray(28)), decipher.final()]).toString(
      "utf8",
    );
  }

  redact(message: string): string {
    try {
      let safe = message;
      for (const row of this.db.select({ id: secrets.id }).from(secrets).all()) {
        const value = this.get(row.id);
        if (value) {
          safe = safe.split(value).join("[REDACTED]");
          if (row.id.startsWith("chatgpt:")) {
            const record: unknown = JSON.parse(value);
            if (typeof record === "object" && record !== null) {
              for (const [field, secret] of Object.entries(record)) {
                if (/token/i.test(field) && typeof secret === "string" && secret) {
                  safe = safe.split(secret).join("[REDACTED]");
                }
              }
            }
          }
        }
      }
      return safe.slice(0, 2000);
    } catch {
      return "凭据校验失败，错误详情已隐藏；请检查数据库密钥";
    }
  }
}
