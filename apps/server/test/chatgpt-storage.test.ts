import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Store } from "../src/store.js";
import { fixtureModel, fixtureTime } from "./fixtures/stored-workspace.js";

describe("ChatGPT credential persistence", () => {
  it("keeps account registrations and encrypted sessions across restarts without changing API key connections", () => {
    const directory = mkdtempSync(join(tmpdir(), "review-chatgpt-storage-"));
    const filename = join(directory, "review.db");
    let store: Store | undefined;
    try {
      store = new Store(filename);
      store.models.save(fixtureModel);
      store.vault.set(`model:${fixtureModel.id}`, "existing-api-key");
      store.chatgptAccounts.save({
        id: "account",
        email: "user@example.com",
        subject: "verified-subject",
        clientId: "oaiapp_registration",
        createdAt: fixtureTime,
        updatedAt: fixtureTime,
      });
      const credentials = JSON.stringify({
        accessToken: "access-token-private",
        refreshToken: "refresh-token-private",
        idToken: "id-token-private",
        expiresAt: Date.now() + 3_600_000,
        scopes: ["chatgpt.tokens.use.direct"],
      });
      store.vault.set("chatgpt:account", credentials);
      store.vault.set("chatgpt-host:id", "urn:uuid:stable-host");
      store.models.save({
        ...fixtureModel,
        id: "account-model",
        baseUrl: "https://api.openai.com/v1",
        authMode: "chatgpt",
        chatgptAccountId: "account",
      });
      expect(
        store.vault.redact("error access-token-private refresh-token-private id-token-private"),
      ).toBe("error [REDACTED] [REDACTED] [REDACTED]");
      store.close();
      store = undefined;
      const database = readFileSync(filename).toString("utf8");
      for (const secret of [
        "existing-api-key",
        "access-token-private",
        "refresh-token-private",
        "id-token-private",
      ]) {
        expect(database).not.toContain(secret);
      }
      store = new Store(filename);
      expect(store.models.get(fixtureModel.id)).toMatchObject({
        authMode: "api-key",
        hasApiKey: true,
      });
      expect(store.models.get("account-model")).toMatchObject({
        authMode: "chatgpt",
        chatgptAccountId: "account",
        hasApiKey: false,
      });
      expect(store.chatgptAccounts.get("account")).toMatchObject({
        clientId: "oaiapp_registration",
        subject: "verified-subject",
      });
      expect(store.vault.get("chatgpt:account")).toBe(credentials);
      expect(store.vault.get("chatgpt-host:id")).toBe("urn:uuid:stable-host");
    } finally {
      store?.close();
      rmSync(directory, { recursive: true, force: true });
    }
  });
});
