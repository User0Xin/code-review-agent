export type DomainErrorCode = "not-found" | "conflict" | "invalid-operation";

export class DomainError extends Error {
  constructor(
    message: string,
    readonly code: DomainErrorCode = "invalid-operation",
  ) {
    super(message);
    this.name = "DomainError";
  }
}
