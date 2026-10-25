import {
  Controller,
  HttpException,
  Inject,
  Param,
  Post,
  Req,
  type RawBodyRequest,
} from "@nestjs/common";
import { providerSchema } from "@review/contracts";
import type { Request } from "express";
import type { WebhookPort } from "../../ports.js";
import { Store } from "../../store.js";
import { WEBHOOKS } from "../../tokens.js";

@Controller("api")
export class WebhooksController {
  constructor(
    @Inject(Store) private readonly store: Store,
    @Inject(WEBHOOKS) private readonly webhooks: WebhookPort,
  ) {}
  @Post("webhooks/:provider/:projectId") webhook(
    @Param("provider") providerValue: string,
    @Param("projectId") id: string,
    @Req() request: RawBodyRequest<Request>,
  ) {
    const provider = providerSchema.parse(providerValue);
    const project = this.store.projects.get(id);
    if (!project || project.provider !== provider) {
      throw new HttpException("项目不存在", 404);
    }
    const headers = Object.fromEntries(
      Object.entries(request.headers).map(([k, v]) => [k, Array.isArray(v) ? v[0] : v]),
    );
    const secret = this.store.vault.get(`webhook:${id}`);
    if (
      !secret ||
      !request.rawBody ||
      !this.webhooks.verify(provider, headers, request.rawBody, secret)
    ) {
      throw new HttpException("Webhook 签名无效", 401);
    }
    const events = this.webhooks
      .normalize(provider, headers, request.body)
      .filter((e) =>
        provider === "github"
          ? e.repository.toLowerCase() === project.repository.toLowerCase()
          : e.repository === project.repository,
      );
    let accepted = 0;
    this.store.transaction(() => {
      for (const event of events) {
        const key = `${id}:${event.id}`;
        if (this.store.events.has(key)) {
          continue;
        }
        this.store.events.record(key, id, provider, event);
        this.store.jobs.enqueue("event", { projectId: id, event }, `event:${key}`);
        accepted++;
      }
    });
    return { accepted };
  }
}
