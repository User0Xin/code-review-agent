import { NestFactory } from "@nestjs/core";
import "reflect-metadata";
import { AppModule, type ApplicationOptions } from "./app.module.js";
import { readEnvironment } from "./configuration/environment.js";
import { ChatGptAuthService } from "./integrations/chatgpt-auth.js";
import { ErrorFilter } from "./http/error.filter.js";
import { originMiddleware } from "./http/origin.middleware.js";
import { Worker } from "./jobs/worker.js";
import type { AgentPort, RepositoryPort, WebhookPort } from "./ports.js";
import { Store } from "./store.js";
import { AGENT, REPOSITORY, WEBHOOKS } from "./tokens.js";
import { Workflow } from "./workflow/workflow.js";

export async function createApp(options: ApplicationOptions = {}) {
  const environment = readEnvironment();
  const app = await NestFactory.create(AppModule.register(options), {
    rawBody: true,
    abortOnError: false,
    forceCloseConnections: true,
    logger: process.env.NODE_ENV === "test" ? false : ["error", "warn", "log"],
  });
  const services = {
    store: app.get(Store),
    chatgpt: app.get(ChatGptAuthService),
    workflow: app.get(Workflow),
    worker: app.get(Worker),
    repository: app.get<RepositoryPort>(REPOSITORY),
    agent: app.get<AgentPort>(AGENT),
    webhooks: app.get<WebhookPort>(WEBHOOKS),
  };
  app.useGlobalFilters(new ErrorFilter(services.store));
  app.use(originMiddleware(environment.port, environment.allowedOrigins));
  if (options.startWorker !== false) {
    services.worker.start();
  }
  let closing: Promise<void> | undefined;
  const close = (): Promise<void> => {
    closing ??= (async () => {
      await services.worker.stop();
      await app.close();
      services.store.close();
    })();
    return closing;
  };
  return { app, services, close };
}
