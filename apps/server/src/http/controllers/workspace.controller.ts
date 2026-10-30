import { Controller, Get, Inject, Param, Post, Sse, type MessageEvent } from "@nestjs/common";
import type { WorkspaceState } from "@review/contracts";
import { interval, map, merge, startWith, type Observable } from "rxjs";
import { requireEntity } from "../../common/require-entity.js";
import { Store } from "../../store.js";
import { Workflow } from "../../workflow/workflow.js";

@Controller("api")
export class WorkspaceController {
  constructor(
    @Inject(Store) private readonly store: Store,
    @Inject(Workflow) private readonly workflow: Workflow,
  ) {}

  @Get("health")
  health() {
    return { status: "ok", version: "0.1.0", storage: "sqlite", mode: "single-instance" };
  }

  @Get("workspace")
  get(): WorkspaceState {
    return {
      models: this.store.models.list(),
      projects: this.store.projects.list(),
      sessions: this.store.sessions.list(),
      jobs: this.store.jobs.list(100),
    };
  }

  @Sse("events")
  events(): Observable<MessageEvent> {
    return merge(
      this.store.changes,
      interval(15_000).pipe(map(() => ({ version: Date.now() }))),
    ).pipe(
      startWith({ version: 0 }),
      map((data): MessageEvent => ({ data })),
    );
  }

  @Post("runs/:id/publish")
  publish(@Param("id") id: string) {
    requireEntity(this.store.runs.get(id));
    return this.workflow.queuePublish(id);
  }

  @Post("jobs/:id/retry")
  retry(@Param("id") id: string) {
    return this.workflow.retryJob(id);
  }
}
