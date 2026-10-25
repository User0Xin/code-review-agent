import { Module, type DynamicModule } from "@nestjs/common";
import { ExperienceService } from "./configuration/experience.service.js";
import { ModelService } from "./configuration/model.service.js";
import { ChatGptController } from "./http/controllers/chatgpt.controller.js";
import { ChatGptAuthService, type ChatGptAuthOptions } from "./integrations/chatgpt-auth.js";
import { ProjectService } from "./configuration/project.service.js";
import { ExperiencesController } from "./http/controllers/experiences.controller.js";
import { ModelsController } from "./http/controllers/models.controller.js";
import { ProjectsController } from "./http/controllers/projects.controller.js";
import { SessionsController } from "./http/controllers/sessions.controller.js";
import { WebhooksController } from "./http/controllers/webhooks.controller.js";
import { WorkspaceController } from "./http/controllers/workspace.controller.js";
import { AiReviewAgent, HttpRepositoryAdapter, WebhookAdapter } from "./integrations/index.js";
import { Worker } from "./jobs/worker.js";
import type { AgentPort, RepositoryPort, WebhookPort } from "./ports.js";
import { Store } from "./store.js";
import { AGENT, REPOSITORY, WEBHOOKS } from "./tokens.js";
import { Workflow } from "./workflow/workflow.js";

export interface ApplicationOptions {
  store?: Store;
  repository?: RepositoryPort;
  agent?: AgentPort;
  webhooks?: WebhookPort;
  startWorker?: boolean;
  chatgptAuth?: ChatGptAuthOptions;
}

@Module({})
export class AppModule {
  static register(options: ApplicationOptions): DynamicModule {
    return {
      module: AppModule,
      controllers: [
        ModelsController,
        ChatGptController,
        ProjectsController,
        SessionsController,
        ExperiencesController,
        WorkspaceController,
        WebhooksController,
      ],
      providers: [
        { provide: Store, useFactory: () => options.store ?? new Store() },
        {
          provide: REPOSITORY,
          useFactory: () => options.repository ?? new HttpRepositoryAdapter(),
        },
        {
          provide: ChatGptAuthService,
          inject: [Store],
          useFactory: (store: Store) => new ChatGptAuthService(store, options.chatgptAuth),
        },
        {
          provide: AGENT,
          inject: [ChatGptAuthService],
          useFactory: (chatgpt: ChatGptAuthService) =>
            options.agent ??
            new AiReviewAgent({ chatgptAccessToken: (id) => chatgpt.accessToken(id) }),
        },
        { provide: WEBHOOKS, useFactory: () => options.webhooks ?? new WebhookAdapter() },
        {
          provide: Workflow,
          inject: [Store, REPOSITORY, AGENT],
          useFactory: (store: Store, repository: RepositoryPort, agent: AgentPort) =>
            new Workflow(store, repository, agent),
        },
        {
          provide: Worker,
          inject: [Store, Workflow],
          useFactory: (store: Store, workflow: Workflow) => new Worker(store, workflow),
        },
        {
          provide: ModelService,
          inject: [Store, AGENT, ChatGptAuthService],
          useFactory: (store: Store, agent: AgentPort, chatgpt: ChatGptAuthService) =>
            new ModelService(store, agent, chatgpt),
        },
        {
          provide: ProjectService,
          inject: [Store],
          useFactory: (store: Store) => new ProjectService(store),
        },
        {
          provide: ExperienceService,
          inject: [Store],
          useFactory: (store: Store) => new ExperienceService(store),
        },
      ],
    };
  }
}
