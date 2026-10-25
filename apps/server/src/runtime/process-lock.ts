import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

function processExists(pid: number): boolean {
  if (!Number.isSafeInteger(pid) || pid <= 0) {
    return false;
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error instanceof Error && "code" in error && error.code === "EPERM";
  }
}

export function acquireProcessLock(databasePath: string): () => void {
  const lockPath = `${databasePath}.lock`;
  mkdirSync(dirname(databasePath), { recursive: true, mode: 0o700 });
  if (existsSync(lockPath)) {
    if (processExists(Number(readFileSync(lockPath, "utf8")))) {
      throw new Error("已有审查服务使用此数据库，请先关闭旧实例");
    }
    unlinkSync(lockPath);
  }
  writeFileSync(lockPath, String(process.pid), { flag: "wx", mode: 0o600 });
  return () => {
    if (existsSync(lockPath)) {
      unlinkSync(lockPath);
    }
  };
}
