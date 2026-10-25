import type Database from "better-sqlite3";
import { readdirSync, readFileSync } from "node:fs";

/** Creates the current schema without changing existing tables or deleting data. */
export function initializeDatabase(connection: Database.Database): void {
  const directory = new URL("../../sql/", import.meta.url);
  const files = readdirSync(directory)
    .filter((file) => file.endsWith(".sql"))
    .sort();
  if (files.length === 0) {
    throw new Error("数据库初始化 SQL 缺失");
  }
  const definitions = files.map((file) => readFileSync(new URL(file, directory), "utf8"));
  connection
    .transaction(() => {
      for (const definition of definitions) {
        connection.exec(definition);
      }
      const violations: unknown = connection.pragma("foreign_key_check");
      if (Array.isArray(violations) && violations.length > 0) {
        throw new Error("数据库存在无效业务关联，初始化已回滚");
      }
    })
    .immediate();
}
