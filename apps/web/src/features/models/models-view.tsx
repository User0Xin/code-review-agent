import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Empty } from "@/components/ui/empty";
import { api, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { type ChatGptAccount, type ModelConnection } from "@review/contracts";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cable, Cpu, LoaderCircle, Plus, Trash2 } from "lucide-react";
import { useState } from "react";

export function ModelsView({
  models,
  onEdit,
}: {
  models: ModelConnection[];
  onEdit: (m?: ModelConnection) => void;
}) {
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const [remove, setRemove] = useState<ModelConnection | null>(null);
  const accounts = useQuery({
    queryKey: ["chatgpt", "accounts"],
    queryFn: () => api<ChatGptAccount[]>("/chatgpt/accounts"),
    enabled: models.some((model) => model.authMode === "chatgpt"),
  });
  const test = useMutation({
    mutationFn: (id: string) => post<{ message: string }>(`/models/${id}/test`),
    onSuccess: (r) => notify(r.message),
    onError: (e) => notify(e.message, true),
  });
  const del = useMutation({
    mutationFn: (id: string) => api(`/models/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void query.invalidateQueries();
      setRemove(null);
      notify("模型连接已删除");
    },
    onError: (e) => notify(e.message, true),
  });
  return (
    <div className="page">
      <header className="page-heading compact">
        <div>
          <h1>模型连接</h1>
        </div>
        <Button onClick={() => onEdit()}>
          <Plus />
          添加模型
        </Button>
      </header>
      {models.length ? (
        <div className="model-list">
          {models.map((m) => {
            const account = accounts.data?.find((item) => item.id === m.chatgptAccountId);
            return (
              <article className="model-card" key={m.id}>
                <div className="model-card-heading">
                  <span className="model-symbol">
                    <Cpu size={24} />
                  </span>
                  <div>
                    <h2>{m.name}</h2>
                    <p>{m.model}</p>
                  </div>
                  <Badge variant={m.enabled ? "green" : "default"}>
                    {m.enabled ? "已启用" : "已禁用"}
                  </Badge>
                </div>
                <dl className="details-list">
                  <div>
                    <dt>连接方式</dt>
                    <dd>{m.authMode === "chatgpt" ? "Codex / ChatGPT 账号" : "API Key"}</dd>
                  </div>
                  {m.authMode === "chatgpt" ? (
                    <>
                      <div>
                        <dt>账号</dt>
                        <dd className="break-all">
                          {account
                            ? `${account.email} · ${account.id.slice(-8)}`
                            : accounts.isPending
                              ? "正在读取…"
                              : "账号不可用"}
                        </dd>
                      </div>
                      <div>
                        <dt>登录状态</dt>
                        <dd>
                          {accounts.isPending
                            ? "正在读取…"
                            : accounts.error
                              ? "无法读取账号"
                              : account?.connected
                                ? "已登录"
                                : "需要登录"}
                        </dd>
                      </div>
                    </>
                  ) : (
                    <>
                      <div>
                        <dt>API 地址</dt>
                        <dd className="break-all">{m.baseUrl}</dd>
                      </div>
                      <div>
                        <dt>API Key</dt>
                        <dd>{m.hasApiKey ? "已保存" : "未配置"}</dd>
                      </div>
                    </>
                  )}
                </dl>
                <footer>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => test.mutate(m.id)}
                    disabled={test.isPending}
                  >
                    {test.isPending && test.variables === m.id ? (
                      <LoaderCircle className="animate-spin" />
                    ) : (
                      <Cable size={14} />
                    )}
                    测试连接
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => onEdit(m)}>
                    {m.authMode === "chatgpt" && !account?.connected ? "编辑 / 登录" : "编辑"}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`删除 ${m.name}`}
                    onClick={() => setRemove(m)}
                  >
                    <Trash2 size={15} />
                  </Button>
                </footer>
              </article>
            );
          })}
        </div>
      ) : (
        <Empty className="empty-panel">
          <Cpu size={38} />
          <h2>尚未添加模型</h2>
          <p>配置 OpenAI 兼容 API，或登录 Codex / ChatGPT 账号。</p>
          <Button onClick={() => onEdit()}>
            <Plus />
            添加模型
          </Button>
        </Empty>
      )}
      <Dialog
        open={!!remove}
        onOpenChange={(v) => {
          if (!v) {
            setRemove(null);
          }
        }}
      >
        <DialogContent className="form-dialog">
          <DialogHeader>
            <DialogTitle>删除模型连接</DialogTitle>
            <DialogDescription>
              删除「{remove?.name}
              」模型连接。如果仓库仍在使用此连接，请先修改仓库的模型配置。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRemove(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={del.isPending}
              onClick={() => remove && del.mutate(remove.id)}
            >
              删除连接
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
