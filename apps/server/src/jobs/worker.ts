import { DomainError } from "../common/domain-error.js";
import { IntegrationError } from "../integrations/integration-error.js";
import type { Store } from "../store.js";
import { errorText } from "../workflow/utils.js";
import type { Workflow } from "../workflow/workflow.js";

/** Single-instance executor; SQLite jobs provide durability, retries, and restart recovery. */
export class Worker {
  private timer?: ReturnType<typeof setInterval>;
  private active?: Promise<void>;
  private stopped = false;
  constructor(
    private readonly store: Store,
    private readonly workflow: Workflow,
  ) {}
  start(): void {
    if (this.timer) {
      return;
    }
    this.stopped = false;
    this.store.jobs.recover();
    this.timer = setInterval(() => {
      void this.drain();
    }, 500);
    this.timer.unref();
    void this.drain();
  }
  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) {
      clearInterval(this.timer);
    }
    this.timer = undefined;
    await this.active;
  }
  async drain(): Promise<void> {
    if (this.active) {
      return this.active;
    }
    this.active = (async () => {
      while (!this.stopped) {
        const job = this.store.jobs.claim();
        if (!job) {
          break;
        }
        try {
          await this.workflow.processJob(job);
          this.store.jobs.complete(job.id);
        } catch (error) {
          const retryable =
            error instanceof DomainError || error instanceof IntegrationError
              ? error.retryable
              : true;
          this.store.jobs.fail(job.id, errorText(error), retryable);
        }
      }
    })();
    try {
      await this.active;
    } finally {
      this.active = undefined;
    }
  }
}
