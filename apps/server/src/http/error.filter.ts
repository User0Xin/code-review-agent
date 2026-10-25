import { Catch, HttpException, type ArgumentsHost, type ExceptionFilter } from "@nestjs/common";
import type { Response } from "express";
import { z } from "zod";
import { DomainError } from "../common/domain-error.js";
import { IntegrationError } from "../integrations/integration-error.js";
import type { Store } from "../store.js";

const domainStatus = { "not-found": 404, conflict: 409, "invalid-operation": 400 } as const;

@Catch()
export class ErrorFilter implements ExceptionFilter {
  constructor(private readonly store: Store) {}

  catch(error: unknown, host: ArgumentsHost): void {
    const response = host.switchToHttp().getResponse<Response>();
    if (response.headersSent) {
      return;
    }
    if (error instanceof z.ZodError) {
      const message = error.issues
        .map((issue) => `${issue.path.join(".")}: ${issue.message}`)
        .join("; ");
      response.status(400).json({ message: this.store.vault.redact(message) });
      return;
    }
    const status =
      error instanceof HttpException
        ? error.getStatus()
        : error instanceof DomainError
          ? domainStatus[error.code]
          : error instanceof IntegrationError
            ? 502
            : 500;
    const message =
      status === 500 ? "服务内部错误" : error instanceof Error ? error.message : "请求失败";
    response.status(status).json({ message: this.store.vault.redact(message) });
  }
}
