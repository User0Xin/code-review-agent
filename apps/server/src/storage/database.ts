import type Database from "better-sqlite3";
import { drizzle } from "drizzle-orm/better-sqlite3";
import * as schema from "./schema.js";

export function createDatabase(connection: Database.Database) {
  return drizzle(connection, { schema });
}

export type ReviewDatabase = ReturnType<typeof createDatabase>;
export type OnChange = () => void;
