import { Body, Controller, Get, Inject, Param, Post } from "@nestjs/common";
import { messageInputSchema, type SessionDetail } from "@review/contracts";
import { requireEntity } from "../../common/require-entity.js";
import { Store } from "../../store.js";
import { Workflow } from "../../workflow/workflow.js";

@Controller("api/sessions")
export class SessionsController {
  constructor(
    @Inject(Store) private readonly store: Store,
    @Inject(Workflow) private readonly workflow: Workflow,
  ) {}

  @Get(":id")
  get(@Param("id") id: string): SessionDetail {
    return {
      session: requireEntity(this.store.sessions.get(id)),
      runs: this.store.runs.listBySession(id),
      findings: this.store.findings.listBySession(id),
      messages: this.store.messages.listBySession(id),
    };
  }

  @Post(":id/review")
  review(@Param("id") id: string) {
    requireEntity(this.store.sessions.get(id));
    return this.workflow.queueReview(id);
  }

  @Post(":id/messages")
  message(@Param("id") id: string, @Body() body: unknown) {
    requireEntity(this.store.sessions.get(id));
    const input = messageInputSchema.parse(body);
    return this.workflow.queueMessage(id, input.body, input.visibility, input.findingId);
  }
}
