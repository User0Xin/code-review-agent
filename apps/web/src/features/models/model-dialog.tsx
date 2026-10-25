import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { patch, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { zodResolver } from "@hookform/resolvers/zod";
import { modelInputSchema, type ModelConnection } from "@review/contracts";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useId, useRef, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { type z } from "zod";

import { Field } from "@/components/form-field";
import { ChatGptAccountFields } from "./chatgpt-account-fields";
export function ModelDialog({
  open,
  onClose,
  model,
}: {
  open: boolean;
  onClose: () => void;
  model?: ModelConnection;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) {
          onClose();
        }
      }}
    >
      <DialogContent className="form-dialog">
        <DialogHeader>
          <DialogTitle>{model ? "编辑模型连接" : "添加模型"}</DialogTitle>
          <DialogDescription>使用 OpenAI 兼容 API，或登录 Codex / ChatGPT 账号。</DialogDescription>
        </DialogHeader>
        {open && <ModelForm key={model?.id || "new"} model={model} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function ModelForm({ model, onClose }: { model?: ModelConnection; onClose: () => void }) {
  const id = useId();
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const [accountReady, setAccountReady] = useState(false);
  const apiBaseUrl = useRef(
    model?.authMode === "chatgpt" ? "https://api.openai.com/v1" : model?.baseUrl,
  );
  const f = useForm<z.input<typeof modelInputSchema>, unknown, z.output<typeof modelInputSchema>>({
    resolver: zodResolver(modelInputSchema),
    defaultValues: {
      name: model?.name || "",
      baseUrl: model?.baseUrl || "https://api.openai.com/v1",
      model: model?.model || "",
      apiKey: "",
      authMode: model?.authMode ?? "api-key",
      chatgptAccountId: model?.chatgptAccountId,
      enabled: model?.enabled ?? true,
    },
  });
  const authMode = f.watch("authMode") ?? "api-key";
  const accountId = f.watch("chatgptAccountId") ?? "";
  const modelId = f.watch("model");
  const enabled = f.watch("enabled");
  const unchangedChatGptConnection =
    authMode === "chatgpt" &&
    model?.authMode === "chatgpt" &&
    model.chatgptAccountId === accountId &&
    model.model === modelId;
  const canSave =
    authMode !== "chatgpt" || accountReady || (unchangedChatGptConnection && !enabled);
  const m = useMutation({
    mutationFn: (value: z.output<typeof modelInputSchema>) => {
      const payload = {
        ...value,
        apiKey:
          value.authMode === "chatgpt"
            ? undefined
            : model
              ? value.apiKey || undefined
              : value.apiKey,
        chatgptAccountId: value.authMode === "chatgpt" ? value.chatgptAccountId : undefined,
      };
      return model ? patch(`/models/${model.id}`, payload) : post("/models", payload);
    },
    onSuccess: () => {
      void query.invalidateQueries();
      notify("模型连接已保存");
      onClose();
    },
  });
  const e = f.formState.errors;
  return (
    <form
      onSubmit={(event) => {
        void f.handleSubmit((v) => {
          if (canSave) {
            m.mutate(v);
          }
        })(event);
      }}
      className="form-stack"
    >
      <Field label="连接名称" id={`${id}-name`} error={e.name?.message}>
        <Input id={`${id}-name`} placeholder="团队审查模型" {...f.register("name")} />
      </Field>
      <Field label="连接方式" id={`${id}-auth`}>
        <Controller
          control={f.control}
          name="authMode"
          render={({ field }) => (
            <Select
              {...field}
              id={`${id}-auth`}
              value={field.value ?? "api-key"}
              disabled={m.isPending}
              onValueChange={(value) => {
                const next = value === "chatgpt" ? "chatgpt" : "api-key";
                if (authMode === "api-key") {
                  apiBaseUrl.current = f.getValues("baseUrl");
                }
                field.onChange(next);
                f.setValue(
                  "baseUrl",
                  next === "chatgpt"
                    ? "https://api.openai.com/v1"
                    : (apiBaseUrl.current ?? "https://api.openai.com/v1"),
                );
                f.setValue("apiKey", undefined);
                f.setValue("chatgptAccountId", undefined);
                f.setValue("model", "");
                f.clearErrors();
                setAccountReady(false);
              }}
              options={[
                { value: "api-key", label: "API Key" },
                { value: "chatgpt", label: "Codex / ChatGPT 账号" },
              ]}
            />
          )}
        />
      </Field>
      {authMode === "chatgpt" ? (
        <Controller
          control={f.control}
          name="chatgptAccountId"
          render={({ field: accountField }) => (
            <Controller
              control={f.control}
              name="model"
              render={({ field: modelField }) => (
                <ChatGptAccountFields
                  accountId={accountField.value ?? ""}
                  modelId={modelField.value ?? ""}
                  disabled={m.isPending}
                  accountInputProps={{
                    name: accountField.name,
                    ref: accountField.ref,
                    onBlur: accountField.onBlur,
                  }}
                  modelInputProps={{
                    name: modelField.name,
                    ref: modelField.ref,
                    onBlur: modelField.onBlur,
                  }}
                  onAccountChange={(value) => {
                    accountField.onChange(value);
                    if (value !== accountId) {
                      f.setValue("model", "");
                      setAccountReady(false);
                    }
                  }}
                  onModelChange={(value) => {
                    modelField.onChange(value);
                    void f.trigger("model");
                  }}
                  onReadyChange={setAccountReady}
                  accountError={e.chatgptAccountId?.message}
                  modelError={e.model?.message}
                />
              )}
            />
          )}
        />
      ) : (
        <>
          <Field label="API 地址" id={`${id}-url`} error={e.baseUrl?.message}>
            <Input id={`${id}-url`} {...f.register("baseUrl")} />
          </Field>
          <Field label="模型标识" id={`${id}-model`} error={e.model?.message}>
            <Input
              id={`${id}-model`}
              placeholder="填写服务提供方的模型 ID"
              {...f.register("model")}
            />
          </Field>
          <Field
            label="API Key"
            id={`${id}-key`}
            error={e.apiKey?.message}
            hint={
              model?.hasApiKey && model.authMode !== "chatgpt" ? "已保存，留空保留。" : undefined
            }
          >
            <Input
              id={`${id}-key`}
              type="password"
              autoComplete="new-password"
              {...f.register("apiKey")}
            />
          </Field>
        </>
      )}
      <div className="toggle-row">
        <Label htmlFor={`${id}-enabled`}>启用此连接</Label>
        <Switch
          id={`${id}-enabled`}
          checked={enabled}
          onCheckedChange={(v) => f.setValue("enabled", v)}
        />
      </div>
      {unchangedChatGptConnection && !accountReady && (
        <p className="field-hint">保留原账号和模型时，可关闭“启用此连接”后保存，无需重新登录。</p>
      )}
      {m.error && (
        <p className="field-error" role="alert">
          {m.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          取消
        </Button>
        <Button disabled={m.isPending || !canSave} type="submit">
          {m.isPending && <LoaderCircle className="animate-spin" />}保存连接
        </Button>
      </DialogFooter>
    </form>
  );
}
