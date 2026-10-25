import type { Project, ProjectInput } from "@review/contracts";
import { randomUUID } from "node:crypto";
import { DomainError } from "../common/domain-error.js";
import { requireEntity } from "../common/require-entity.js";
import type { Store } from "../store.js";

export class ProjectService {
  constructor(private readonly store: Store) {}
  update(id: string, input: ProjectInput) {
    requireEntity(this.store.projects.get(id));
    return this.save(input, id);
  }

  save(input: ProjectInput, id: string = randomUUID()): Project {
    const store = this.store;
    const old = store.projects.get(id);
    const now = new Date().toISOString();
    requireEntity(this.store.models.get(input.modelId));
    const host = input.host.replace(/\/$/, "");
    if (
      old &&
      (old.provider !== input.provider || old.host !== host || old.repository !== input.repository)
    ) {
      throw new DomainError("仓库身份不能修改，请新建项目绑定其他仓库", "conflict");
    }
    const existing = store.projects.findByRepository(input.provider, host, input.repository);
    if (existing && existing.id !== id) {
      throw new DomainError("此仓库已绑定项目", "conflict");
    }
    const project: Project = {
      id,
      name: input.name,
      provider: input.provider,
      host,
      repository: input.repository,
      modelId: input.modelId,
      standard: input.standard,
      standardVersion: old
        ? old.standard === input.standard
          ? old.standardVersion
          : old.standardVersion + 1
        : 1,
      ignorePatterns: input.ignorePatterns,
      autoReview: input.autoReview,
      autoPublish: input.autoPublish,
      hasToken: input.token !== undefined ? Boolean(input.token) : (old?.hasToken ?? false),
      hasWebhookSecret:
        input.webhookSecret !== undefined
          ? Boolean(input.webhookSecret)
          : (old?.hasWebhookSecret ?? false),
      createdAt: old?.createdAt ?? now,
      updatedAt: now,
    };
    return store.transaction(() => {
      if (input.token !== undefined) {
        store.vault.set(`repo:${id}`, input.token);
      }
      if (input.webhookSecret !== undefined) {
        store.vault.set(`webhook:${id}`, input.webhookSecret);
      }
      return store.projects.save(project);
    });
  }
}
