/** Errors deliberately omit response bodies, URLs, credentials and underlying fetch errors. */
export class IntegrationError extends Error {
  constructor(
    message: string,
    public readonly retryable = false,
    public readonly status?: number,
  ) {
    super(message);
    this.name = "IntegrationError";
  }
}
