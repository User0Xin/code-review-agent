import { DomainError } from "./domain-error.js";

export function requireEntity<T>(entity: T | undefined): T {
  if (entity === undefined) {
    throw new DomainError("记录不存在", "not-found");
  }
  return entity;
}
