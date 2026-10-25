import type { ModelConnection, Project } from "@review/contracts";
import { and, eq, isNull } from "drizzle-orm";
import { DomainError } from "../../common/domain-error.js";
import type { OnChange, ReviewDatabase } from "../database.js";
import {
  experiences,
  modelConnections,
  modelChatgptAccounts,
  projects,
  secrets,
  standardVersions,
} from "../schema.js";

export class ModelRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): ModelConnection[] {
    return this.db
      .select()
      .from(modelConnections)
      .where(isNull(modelConnections.archivedAt))
      .all()
      .map((row) => this.toModel(row));
  }

  get(id: string): ModelConnection | undefined {
    const row = this.db.select().from(modelConnections).where(eq(modelConnections.id, id)).get();
    return row ? this.toModel(row) : undefined;
  }

  save(model: ModelConnection): ModelConnection {
    const secretId = `model:${model.id}`;
    const hasKey = this.db
      .select({ id: secrets.id })
      .from(secrets)
      .where(eq(secrets.id, secretId))
      .get();
    const row = {
      id: model.id,
      name: model.name,
      baseUrl: model.baseUrl,
      model: model.model,
      enabled: model.enabled,
      apiKeySecretId: hasKey ? secretId : null,
      createdAt: model.createdAt,
      updatedAt: model.updatedAt,
    };
    this.db
      .insert(modelConnections)
      .values(row)
      .onConflictDoUpdate({ target: modelConnections.id, set: row })
      .run();
    if (model.authMode === "chatgpt" && model.chatgptAccountId) {
      this.db
        .insert(modelChatgptAccounts)
        .values({ modelId: model.id, accountId: model.chatgptAccountId })
        .onConflictDoUpdate({
          target: modelChatgptAccounts.modelId,
          set: { accountId: model.chatgptAccountId },
        })
        .run();
    } else {
      this.db.delete(modelChatgptAccounts).where(eq(modelChatgptAccounts.modelId, model.id)).run();
    }
    this.changed();
    return this.get(model.id) ?? model;
  }

  archive(id: string): void {
    this.db
      .update(modelConnections)
      .set({ archivedAt: new Date().toISOString(), enabled: false })
      .where(eq(modelConnections.id, id))
      .run();
    this.changed();
  }

  private toModel(row: typeof modelConnections.$inferSelect): ModelConnection {
    const account = this.db
      .select()
      .from(modelChatgptAccounts)
      .where(eq(modelChatgptAccounts.modelId, row.id))
      .get();
    return {
      id: row.id,
      name: row.name,
      authMode: account ? "chatgpt" : "api-key",
      ...(account ? { chatgptAccountId: account.accountId } : {}),
      baseUrl: row.baseUrl,
      model: row.model,
      enabled: row.enabled,
      hasApiKey: row.apiKeySecretId !== null,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}

function repositoryKey(provider: Project["provider"], repository: string): string {
  return provider === "github" ? repository.toLowerCase() : repository;
}

export class ProjectRepository {
  constructor(
    private readonly db: ReviewDatabase,
    private readonly changed: OnChange,
  ) {}

  list(): Project[] {
    return this.db
      .select()
      .from(projects)
      .where(isNull(projects.archivedAt))
      .all()
      .map((row) => this.toProject(row));
  }

  get(id: string): Project | undefined {
    const row = this.db.select().from(projects).where(eq(projects.id, id)).get();
    return row ? this.toProject(row) : undefined;
  }

  findByRepository(
    provider: Project["provider"],
    host: string,
    repository: string,
  ): Project | undefined {
    const row = this.db
      .select()
      .from(projects)
      .where(
        and(
          eq(projects.provider, provider),
          eq(projects.host, host),
          eq(projects.repositoryKey, repositoryKey(provider, repository)),
        ),
      )
      .get();
    return row ? this.toProject(row) : undefined;
  }

  save(project: Project, sourceExperienceId?: string): Project {
    return this.db.transaction(() => {
      const existing = this.get(project.id);
      const version = this.getStandardVersion(project.id, project.standardVersion);
      if (version && version.body !== project.standard) {
        throw new DomainError("规范版本已存在，修改规范必须创建新版本", "conflict");
      }
      const tokenSecretId = this.hasSecret(`repo:${project.id}`) ? `repo:${project.id}` : null;
      const webhookSecretId = this.hasSecret(`webhook:${project.id}`)
        ? `webhook:${project.id}`
        : null;
      const row = {
        id: project.id,
        name: project.name,
        provider: project.provider,
        host: project.host,
        repository: project.repository,
        repositoryKey: repositoryKey(project.provider, project.repository),
        modelId: project.modelId,
        standardVersion: project.standardVersion,
        ignorePatterns: project.ignorePatterns,
        autoReview: project.autoReview,
        autoPublish: project.autoPublish,
        tokenSecretId,
        webhookSecretId,
        createdAt: existing?.createdAt ?? project.createdAt,
        updatedAt: project.updatedAt,
      };
      this.db
        .insert(projects)
        .values(row)
        .onConflictDoUpdate({ target: projects.id, set: row })
        .run();
      if (!version) {
        this.ensureStandardVersion(
          project.id,
          project.standardVersion,
          project.standard,
          project.updatedAt,
          sourceExperienceId,
        );
      }
      this.changed();
      return this.get(project.id) ?? project;
    });
  }

  getStandardVersion(projectId: string, version: number) {
    return this.db
      .select()
      .from(standardVersions)
      .where(and(eq(standardVersions.projectId, projectId), eq(standardVersions.version, version)))
      .get();
  }

  listStandardVersions(projectId: string) {
    return this.db
      .select()
      .from(standardVersions)
      .where(eq(standardVersions.projectId, projectId))
      .orderBy(standardVersions.version)
      .all();
  }

  ensureStandardVersion(
    projectId: string,
    version: number,
    body: string,
    createdAt: string,
    sourceExperienceId?: string,
  ): string {
    if (sourceExperienceId) {
      const source = this.db
        .select()
        .from(experiences)
        .where(eq(experiences.id, sourceExperienceId))
        .get();
      if (!source || source.projectId !== projectId) {
        throw new DomainError("规范建议不属于当前项目");
      }
    }
    const existing = this.getStandardVersion(projectId, version);
    if (existing) {
      if (existing.body !== body) {
        throw new DomainError("历史规范正文与版本冲突", "conflict");
      }
      return existing.id;
    }
    const id = `standard:${projectId}:${version}`;
    this.db
      .insert(standardVersions)
      .values({ id, projectId, version, body, sourceExperienceId, createdAt })
      .run();
    return id;
  }

  private hasSecret(id: string): boolean {
    return (
      this.db.select({ id: secrets.id }).from(secrets).where(eq(secrets.id, id)).get() !== undefined
    );
  }

  private toProject(row: typeof projects.$inferSelect): Project {
    const standard = this.getStandardVersion(row.id, row.standardVersion);
    return {
      id: row.id,
      name: row.name,
      provider: row.provider,
      host: row.host,
      repository: row.repository,
      modelId: row.modelId,
      standard: standard?.body ?? "",
      standardVersion: row.standardVersion,
      ignorePatterns: row.ignorePatterns,
      autoReview: row.autoReview,
      autoPublish: row.autoPublish,
      hasToken: row.tokenSecretId !== null,
      hasWebhookSecret: row.webhookSecretId !== null,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
