import { Badge } from "@/components/ui/badge";
import { LoaderCircle } from "lucide-react";
export function time(value: string | number) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

export const statusLabel: Record<string, string> = {
  queued: "排队中",
  running: "进行中",
  completed: "已完成",
  failed: "失败",
  superseded: "已过期",
  open: "待处理",
  resolved: "已解决",
  withdrawn: "已撤回",
  merged: "已合并",
  closed: "已关闭",
  accepted: "已采纳",
  draft: "待确认",
  rejected: "已忽略",
};

export function Status({ value, label }: { value: string; label?: string }) {
  return (
    <Badge
      variant={
        ["completed", "resolved", "accepted", "open"].includes(value)
          ? "green"
          : ["running", "queued", "draft"].includes(value)
            ? "amber"
            : ["failed", "closed"].includes(value)
              ? "red"
              : value === "merged"
                ? "violet"
                : "gray"
      }
      className="inline-flex items-center gap-1"
    >
      {["running", "queued"].includes(value) && (
        <LoaderCircle className={value === "running" ? "animate-spin" : ""} size={11} />
      )}{" "}
      {label || statusLabel[value] || value}
    </Badge>
  );
}
