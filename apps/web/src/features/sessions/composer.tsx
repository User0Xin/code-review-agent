import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { zodResolver } from "@hookform/resolvers/zod";
import { messageInputSchema, type Finding } from "@review/contracts";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowUp, Globe, LoaderCircle, MessageSquare, X } from "lucide-react";
import { useId, useLayoutEffect, useRef } from "react";
import { Controller, useForm } from "react-hook-form";
import { type z } from "zod";

export function Composer({
  sessionId,
  finding,
  onClear,
}: {
  sessionId: string;
  finding: Finding | null;
  onClear: () => void;
}) {
  const id = useId();
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const composingRef = useRef(false);
  const submittingRef = useRef(false);
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const f = useForm<
    z.input<typeof messageInputSchema>,
    unknown,
    z.output<typeof messageInputSchema>
  >({
    resolver: zodResolver(messageInputSchema),
    defaultValues: { body: "", visibility: "internal" },
  });
  const body = f.watch("body") ?? "";
  const bodyField = f.register("body");
  const visibility = f.watch("visibility");
  const bodyError = f.formState.errors.body;
  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return;
    }
    const resize = () => {
      textarea.style.height = "auto";
      textarea.style.height = `${textarea.scrollHeight}px`;
    };
    resize();
    let width = textarea.clientWidth;
    const observer = new ResizeObserver(() => {
      if (textarea.clientWidth !== width) {
        width = textarea.clientWidth;
        resize();
      }
    });
    observer.observe(textarea);
    return () => observer.disconnect();
  }, [body]);
  const send = useMutation({
    mutationFn: (value: z.output<typeof messageInputSchema>) =>
      post(`/sessions/${sessionId}/messages`, {
        ...value,
        findingId: finding?.id,
      }),
    onSuccess: () => {
      f.reset({ body: "", visibility });
      onClear();
      void query.invalidateQueries();
      notify(visibility === "external" ? "评论已加入发布队列" : "消息已发送");
    },
    onError: (e) => notify(e.message, true),
  });
  return (
    <div className="composer-wrap">
      <form
        className="composer"
        aria-busy={send.isPending}
        onSubmit={(event) => {
          event.preventDefault();
          if (submittingRef.current || send.isPending || !f.getValues("body").trim()) {
            return;
          }
          // Lock before async validation so Enter and a click cannot submit twice.
          submittingRef.current = true;
          void (async () => {
            try {
              await f.handleSubmit((value) => send.mutateAsync(value))(event);
            } catch {
              // The mutation's onError already displays the failure message.
            } finally {
              submittingRef.current = false;
            }
          })();
        }}
      >
        {finding && (
          <div className="composer-reference">
            <MessageSquare size={13} aria-hidden="true" />
            <span>关于：{finding.title}</span>
            <Button size="icon-xs" variant="ghost" aria-label="取消回复问题" onClick={onClear}>
              <X size={13} aria-hidden="true" />
            </Button>
          </div>
        )}
        <Textarea
          {...bodyField}
          ref={(element) => {
            textareaRef.current = element;
            bodyField.ref(element);
          }}
          aria-label="输入消息"
          aria-invalid={Boolean(bodyError)}
          aria-describedby={bodyError ? `${id}-error` : undefined}
          placeholder={visibility === "internal" ? "提问或讨论审查结果…" : "输入 PR 评论…"}
          rows={1}
          disabled={send.isPending}
          onCompositionStart={() => {
            composingRef.current = true;
          }}
          onCompositionEnd={() => {
            composingRef.current = false;
          }}
          onKeyDown={(event) => {
            if (
              event.key !== "Enter" ||
              event.shiftKey ||
              event.ctrlKey ||
              event.altKey ||
              event.metaKey ||
              composingRef.current ||
              event.nativeEvent.isComposing ||
              // Safari can end composition before this keydown while still
              // identifying the candidate-confirmation key with code 229.
              event.nativeEvent.keyCode === 229
            ) {
              return;
            }
            event.preventDefault();
            if (!event.repeat && !submittingRef.current && !send.isPending) {
              event.currentTarget.form?.requestSubmit();
            }
          }}
        />
        <div className="composer-toolbar">
          <Controller
            control={f.control}
            name="visibility"
            render={({ field }) => (
              <Select
                {...field}
                value={field.value ?? ""}
                onValueChange={field.onChange}
                aria-label="消息可见范围"
                size="sm"
                className="composer-visibility w-auto"
                disabled={send.isPending}
                options={[
                  { value: "internal", label: "内部讨论" },
                  { value: "external", label: "PR 评论" },
                ]}
              />
            )}
          />
          <Button
            size={visibility === "internal" ? "icon-xs" : "xs"}
            className="composer-send"
            type="submit"
            aria-label={visibility === "internal" ? "发送消息" : "发布到 PR"}
            title={visibility === "internal" ? "发送消息（Enter）" : "发布到 PR（Enter）"}
            disabled={send.isPending || !body.trim()}
          >
            {send.isPending ? (
              <LoaderCircle className="animate-spin" aria-hidden="true" />
            ) : visibility === "internal" ? (
              <ArrowUp aria-hidden="true" />
            ) : (
              <Globe aria-hidden="true" />
            )}
            {visibility === "external" && <span>发布到 PR</span>}
          </Button>
        </div>
        {bodyError && (
          <p id={`${id}-error`} className="field-error composer-error" role="alert">
            请输入 1 至 16000 个字符。
          </p>
        )}
      </form>
    </div>
  );
}
