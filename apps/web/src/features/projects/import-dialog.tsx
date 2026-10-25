import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { zodResolver } from "@hookform/resolvers/zod";
import { importPrSchema, type Project, type Session } from "@review/contracts";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { GitPullRequest, LoaderCircle, Plus } from "lucide-react";
import { useForm } from "react-hook-form";
import { type z } from "zod";

export function ImportDialog({
  open,
  project,
  onClose,
}: {
  open: boolean;
  project: Project;
  onClose: () => void;
}) {
  const q = useQuery({
    queryKey: ["pulls", project.id],
    queryFn: () =>
      api<Array<{ number: number; title: string; url: string; headSha: string }>>(
        `/projects/${project.id}/pulls`,
      ),
    enabled: open,
  });
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const select = useUi((s) => s.selectSession);
  const f = useForm<z.input<typeof importPrSchema>, unknown, z.output<typeof importPrSchema>>({
    resolver: zodResolver(importPrSchema),
  });
  const m = useMutation({
    mutationFn: (number: number) => post<Session>(`/projects/${project.id}/pulls`, { number }),
    onSuccess: (s) => {
      void query.invalidateQueries();
      select(project.id, s.id);
      notify("PR 会话已导入");
      onClose();
      f.reset();
    },
    onError: (e) => notify(e.message, true),
  });
  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) {
          onClose();
        }
      }}
    >
      <DialogContent className="form-dialog import-dialog">
        <DialogHeader>
          <DialogTitle>导入 PR</DialogTitle>
          <DialogDescription>{project.repository}，选择待合并的 PR 或输入编号。</DialogDescription>
        </DialogHeader>
        <form
          className="import-form"
          onSubmit={(event) => {
            void f.handleSubmit((v) => m.mutate(v.number))(event);
          }}
        >
          <Label htmlFor="pr-number" className="sr-only">
            PR 编号
          </Label>
          <Input
            id="pr-number"
            type="number"
            min="1"
            placeholder="PR 编号，例如 42"
            {...f.register("number")}
          />
          <Button type="submit" disabled={m.isPending}>
            {m.isPending ? <LoaderCircle className="animate-spin" /> : <Plus />}
            导入
          </Button>
        </form>
        {f.formState.errors.number && <p className="field-error">请输入大于 0 的 PR 编号。</p>}
        <div className="import-list">
          {q.isPending ? (
            <div className="center-loading">
              <LoaderCircle className="animate-spin" />
              正在获取 PR…
            </div>
          ) : q.error ? (
            <p className="field-error">{q.error.message}</p>
          ) : !q.data?.length ? (
            <p className="muted">暂无待合并的 PR，可输入编号导入。</p>
          ) : (
            q.data.map((p) => (
              <Button
                key={p.number}
                variant="ghost"
                className="import-row"
                disabled={m.isPending}
                onClick={() => m.mutate(p.number)}
              >
                <GitPullRequest size={16} />
                <span>
                  <strong>
                    #{p.number} {p.title}
                  </strong>
                  <small>{p.headSha.slice(0, 8)}</small>
                </span>
                <Plus size={15} />
              </Button>
            ))
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
