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
import { Textarea } from "@/components/ui/textarea";
import { patch, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { zodResolver } from "@hookform/resolvers/zod";
import { projectInputSchema, type ModelConnection, type Project } from "@review/contracts";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, Plus, ShieldCheck } from "lucide-react";
import { useId } from "react";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";

import { Field } from "@/components/form-field";
export function ProjectDialog({
  open,
  onClose,
  project,
  models,
}: {
  open: boolean;
  onClose: () => void;
  project?: Project;
  models: ModelConnection[];
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
      <DialogContent className="form-dialog project-dialog">
        <DialogHeader>
          <DialogTitle>{project ? "项目设置" : "绑定仓库"}</DialogTitle>
          <DialogDescription>
            {project
              ? `${project.repository}，规范 v${project.standardVersion}`
              : "连接 GitHub 或 GitLab 仓库。"}
          </DialogDescription>
        </DialogHeader>
        {open && (
          <ProjectForm
            key={project?.id || "new"}
            project={project}
            models={models}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

const editableProjectSchema = projectInputSchema.extend({
  ignoreText: z.string().default(""),
});

function ProjectForm({
  project,
  models,
  onClose,
}: {
  project?: Project;
  models: ModelConnection[];
  onClose: () => void;
}) {
  const id = useId();
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const select = useUi((s) => s.selectProject);
  const f = useForm<
    z.input<typeof editableProjectSchema>,
    unknown,
    z.output<typeof editableProjectSchema>
  >({
    resolver: zodResolver(editableProjectSchema),
    defaultValues: {
      name: project?.name || "",
      provider: project?.provider || "github",
      host: project?.host || "https://github.com",
      repository: project?.repository || "",
      modelId: project?.modelId || models.find((x) => x.enabled)?.id || "",
      token: "",
      webhookSecret: "",
      standard: project?.standard || "",
      ignorePatterns: project?.ignorePatterns,
      ignoreText: (
        project?.ignorePatterns || ["**/node_modules/**", "**/dist/**", "**/*lock*"]
      ).join("\n"),
      autoReview: project?.autoReview ?? true,
      autoPublish: project?.autoPublish ?? false,
    },
  });
  const m = useMutation({
    mutationFn: ({ ignoreText, ...value }: z.output<typeof editableProjectSchema>) => {
      const payload = {
        ...value,
        ignorePatterns: ignoreText
          .split("\n")
          .map((x) => x.trim())
          .filter(Boolean),
        token: value.token || undefined,
        webhookSecret: value.webhookSecret || undefined,
      };
      return project
        ? patch<Project>(`/projects/${project.id}`, payload)
        : post<Project>("/projects", payload);
    },
    onSuccess: (p) => {
      void query.invalidateQueries();
      notify("仓库配置已保存");
      select(p.id);
      onClose();
    },
  });
  const e = f.formState.errors;
  return (
    <form
      className="form-stack"
      onSubmit={(event) => {
        void f.handleSubmit((v) => m.mutate(v))(event);
      }}
    >
      <div className="form-grid">
        <Field label="项目名称" id={`${id}-name`} error={e.name?.message}>
          <Input id={`${id}-name`} placeholder="我的项目" {...f.register("name")} />
        </Field>
        <Field label="平台" id={`${id}-provider`} error={e.provider?.message}>
          <Controller
            control={f.control}
            name="provider"
            render={({ field }) => (
              <Select
                {...field}
                id={`${id}-provider`}
                value={field.value ?? ""}
                onValueChange={(value) => {
                  field.onChange(value);
                  f.setValue(
                    "host",
                    value === "github" ? "https://github.com" : "https://gitlab.com",
                  );
                }}
                disabled={!!project}
                options={[
                  { value: "github", label: "GitHub" },
                  { value: "gitlab", label: "GitLab" },
                ]}
              />
            )}
          />
        </Field>
        <Field label="平台地址" id={`${id}-host`} error={e.host?.message}>
          <Input id={`${id}-host`} readOnly={!!project} {...f.register("host")} />
        </Field>
        <Field label="仓库路径" id={`${id}-repo`} error={e.repository?.message}>
          <Input
            id={`${id}-repo`}
            placeholder="owner/repository"
            readOnly={!!project}
            {...f.register("repository")}
          />
        </Field>
      </div>
      <Field label="审查模型" id={`${id}-model`} error={e.modelId?.message}>
        <Controller
          control={f.control}
          name="modelId"
          render={({ field }) => (
            <Select
              {...field}
              id={`${id}-model`}
              value={field.value ?? ""}
              onValueChange={field.onChange}
              searchable
              searchPlaceholder="搜索审查模型…"
              options={[
                { value: "", label: "请选择已启用的模型" },
                ...models.map((model) => ({
                  value: model.id,
                  label: `${model.name}（${model.model}）${!model.enabled ? "（已禁用）" : ""}`,
                  disabled: !model.enabled,
                })),
              ]}
            />
          )}
        />
      </Field>
      <div className="form-grid">
        <Field
          label="仓库访问令牌"
          id={`${id}-token`}
          error={e.token?.message}
          hint={
            project?.hasToken ? "令牌已保存，留空保留。" : "需要读取 PR、代码及发布评论的权限。"
          }
        >
          <Input
            type="password"
            autoComplete="new-password"
            id={`${id}-token`}
            {...f.register("token")}
          />
        </Field>
        <Field
          label="Webhook Secret"
          id={`${id}-secret`}
          error={e.webhookSecret?.message}
          hint={
            project?.hasWebhookSecret ? "密钥已保存，留空保留。" : "配置后用于验证仓库平台事件。"
          }
        >
          <Input
            type="password"
            autoComplete="new-password"
            id={`${id}-secret`}
            {...f.register("webhookSecret")}
          />
        </Field>
      </div>
      {project && (
        <div className="subtle-note">
          <ShieldCheck size={15} />
          <div>
            Webhook 路径
            <code>
              /api/webhooks/{project.provider}/{project.id}
            </code>
            <small>拼接部署后的外部可访问地址。开发代理地址不能直接接收平台事件。</small>
          </div>
        </div>
      )}
      <Field label="审查规范" id={`${id}-standard`} error={e.standard?.message}>
        <Textarea
          id={`${id}-standard`}
          className="standard-input"
          placeholder="例如：所有外部输入必须校验；数据库写入使用事务；错误需保留上下文……"
          {...f.register("standard")}
        />
      </Field>
      <Field
        label="忽略路径（每行一个 glob 模式）"
        id={`${id}-ignore`}
        error={e.ignorePatterns?.message}
      >
        <Textarea id={`${id}-ignore`} rows={3} {...f.register("ignoreText")} />
      </Field>
      <div className="toggle-row">
        <div>
          <Label htmlFor={`${id}-review`}>自动审查新 PR 和提交</Label>
          <p className="field-hint">需配置仓库 Webhook。</p>
        </div>
        <Switch
          id={`${id}-review`}
          checked={f.watch("autoReview")}
          onCheckedChange={(v) => f.setValue("autoReview", v)}
        />
      </div>
      <div className="toggle-row">
        <div>
          <Label htmlFor={`${id}-publish`}>自动发布审查评论</Label>
          <p className="field-hint">开启后，完成审查即向 PR 写入评论。</p>
        </div>
        <Switch
          id={`${id}-publish`}
          checked={f.watch("autoPublish")}
          onCheckedChange={(v) => f.setValue("autoPublish", v)}
        />
      </div>
      {m.error && (
        <p className="field-error" role="alert">
          {m.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          取消
        </Button>
        <Button type="submit" disabled={m.isPending || !models.length}>
          {m.isPending ? <LoaderCircle className="animate-spin" /> : <Plus />}
          {project ? "保存设置" : "绑定仓库"}
        </Button>
      </DialogFooter>
    </form>
  );
}
