import Database from "better-sqlite3";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { initializeDatabase } from "../src/storage/initialize-database.js";

const cleanups: Array<() => void> = [];

afterEach(() => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    cleanup();
  }
});

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "review-initialization-"));
  cleanups.push(() => rmSync(directory, { recursive: true, force: true }));
  const filename = join(directory, "review.db");
  const open = () => {
    const connection = new Database(filename);
    connection.pragma("foreign_keys = ON");
    cleanups.push(() => connection.close());
    return connection;
  };
  return { connection: open(), open };
}

function readSchema(connection: Database.Database) {
  return connection
    .prepare("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")
    .all();
}

describe("database initialization", () => {
  it("creates the domain tables without a migration ledger or schema version", () => {
    const { connection } = fixture();

    initializeDatabase(connection);

    expect(
      connection
        .prepare(
          "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        )
        .all(),
    ).toEqual(
      [
        "chatgpt_accounts",
        "experience_sources",
        "experiences",
        "findings",
        "jobs",
        "messages",
        "model_chatgpt_accounts",
        "model_connections",
        "pr_snapshots",
        "project_standard_versions",
        "projects",
        "publications",
        "review_run_findings",
        "review_runs",
        "secrets",
        "sessions",
        "webhook_events",
      ].map((name) => ({ name })),
    );
    expect(
      connection
        .prepare("SELECT name FROM sqlite_master WHERE name = '__drizzle_migrations'")
        .get(),
    ).toBeUndefined();
    expect(connection.pragma("user_version", { simple: true })).toBe(0);
    expect(connection.pragma("foreign_key_check")).toEqual([]);
  });

  it("preserves persisted data and existing metadata across repeated initialization and restart", () => {
    const { connection, open } = fixture();
    initializeDatabase(connection);
    connection.pragma("user_version = 17");
    connection.prepare("INSERT INTO secrets(id, value) VALUES (?, ?)").run("model:m", "encrypted");
    connection
      .prepare(
        "INSERT INTO model_connections(id, name, base_url, model, enabled, api_key_secret_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      )
      .run("m", "Saved model", "http://localhost:1234/v1", "local-model", 1, "model:m", "t", "t");
    const schema = readSchema(connection);

    initializeDatabase(connection);
    expect(readSchema(connection)).toEqual(schema);
    connection.close();

    const restarted = open();
    initializeDatabase(restarted);

    expect(restarted.prepare("SELECT id, value FROM secrets").all()).toEqual([
      { id: "model:m", value: "encrypted" },
    ]);
    expect(
      restarted.prepare("SELECT id, name, api_key_secret_id FROM model_connections").all(),
    ).toEqual([{ id: "m", name: "Saved model", api_key_secret_id: "model:m" }]);
    expect(readSchema(restarted)).toEqual(schema);
    expect(restarted.pragma("user_version", { simple: true })).toBe(17);
    expect(restarted.pragma("foreign_key_check")).toEqual([]);
  });

  it("rolls back initialization when an existing table has an incompatible structure", () => {
    const { connection } = fixture();
    connection.exec("CREATE TABLE projects (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
    connection
      .prepare("INSERT INTO projects(id, data) VALUES (?, ?)")
      .run("legacy-project", JSON.stringify({ name: "Preserve me" }));
    const schema = readSchema(connection);

    expect(() => initializeDatabase(connection)).toThrow();

    expect(readSchema(connection)).toEqual(schema);
    expect(connection.prepare("SELECT id, data FROM projects").all()).toEqual([
      { id: "legacy-project", data: JSON.stringify({ name: "Preserve me" }) },
    ]);
    expect(connection.pragma("user_version", { simple: true })).toBe(0);
    expect(connection.inTransaction).toBe(false);
  });
});
