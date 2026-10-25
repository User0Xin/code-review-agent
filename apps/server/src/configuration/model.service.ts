import { CHATGPT_BASE_URL, type ModelConnection, type ModelInput } from "@review/contracts";
import { randomUUID } from "node:crypto";
import { DomainError } from "../common/domain-error.js";
import { requireEntity } from "../common/require-entity.js";
import type { AgentPort } from "../ports.js";
import type { Store } from "../store.js";
import type { ChatGptAuthService } from "../integrations/chatgpt-auth.js";

export class ModelService {
  constructor(
    private readonly store: Store,
    private readonly agent: AgentPort,
    private readonly chatgpt: ChatGptAuthService,
  ) {}
  update(id: string, input: ModelInput) {
    requireEntity(this.store.models.get(id));
    return this.save(input, id);
  }

  save(input: ModelInput, id: string = randomUUID()): ModelConnection {
    const store = this.store;
    const old = store.models.get(id);
    const authMode = input.authMode ?? "api-key";
    const disablingExistingAccount =
      old?.authMode === "chatgpt" &&
      old.chatgptAccountId === input.chatgptAccountId &&
      old.model === input.model &&
      !input.enabled;
    if (
      authMode === "chatgpt" &&
      !disablingExistingAccount &&
      !this.chatgpt
        .accounts()
        .some((account) => account.id === input.chatgptAccountId && account.connected)
    ) {
      throw new DomainError("请先登录并授权 ChatGPT 账号，再保存模型连接");
    }
    const now = new Date().toISOString();
    const model: ModelConnection = {
      id,
      name: input.name,
      authMode,
      ...(authMode === "chatgpt" ? { chatgptAccountId: input.chatgptAccountId } : {}),
      model: input.model,
      baseUrl: authMode === "chatgpt" ? CHATGPT_BASE_URL : input.baseUrl.replace(/\/$/, ""),
      enabled: input.enabled,
      hasApiKey:
        authMode === "api-key" &&
        (input.apiKey !== undefined ? Boolean(input.apiKey) : (old?.hasApiKey ?? false)),
      createdAt: old?.createdAt ?? now,
      updatedAt: now,
    };
    return store.transaction(() => {
      if (authMode === "chatgpt") {
        store.vault.set(`model:${id}`, "");
      } else if (input.apiKey !== undefined) {
        store.vault.set(`model:${id}`, input.apiKey);
      }
      return store.models.save(model);
    });
  }
  remove(id: string) {
    requireEntity(this.store.models.get(id));
    if (this.store.projects.list().some((p) => p.modelId === id)) {
      throw new DomainError("模型正在被项目使用，请先更换项目模型", "conflict");
    }
    this.store.transaction(() => {
      this.store.models.archive(id);
      this.store.vault.set(`model:${id}`, "");
    });
    return { deleted: true };
  }
  async test(id: string): Promise<{ message: string }> {
    const model = requireEntity(this.store.models.get(id));
    return { message: await this.agent.test(model, this.store.vault.get(`model:${id}`)) };
  }
}
