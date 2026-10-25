import { Field } from "@/components/form-field";
import { Button, ButtonLink } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { api, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import type { ChatGptAccount, ChatGptLoginAttempt, ChatGptModel } from "@review/contracts";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLink, LoaderCircle } from "lucide-react";
import { type ComponentProps, useEffect, useId, useRef, useState } from "react";

type SelectInputProps = Pick<ComponentProps<typeof Select>, "name" | "ref" | "onBlur">;

function closePopup(popup: Window | null) {
  try {
    popup?.close();
  } catch {
    return;
  }
}

export function ChatGptAccountFields({
  accountId,
  modelId,
  disabled,
  onAccountChange,
  onModelChange,
  onReadyChange,
  accountError,
  modelError,
  accountInputProps,
  modelInputProps,
}: {
  accountId: string;
  modelId: string;
  disabled: boolean;
  onAccountChange: (value: string) => void;
  onModelChange: (value: string) => void;
  onReadyChange: (ready: boolean) => void;
  accountError?: string;
  modelError?: string;
  accountInputProps?: SelectInputProps;
  modelInputProps?: SelectInputProps;
}) {
  const id = useId();
  const query = useQueryClient();
  const notify = useUi((state) => state.notify);
  const [loginId, setLoginId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [loginError, setLoginError] = useState<string>();
  const popup = useRef<Window | null>(null);
  const activeAttempt = useRef<string | null>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const handled = useRef<string | undefined>(undefined);
  const accounts = useQuery({
    queryKey: ["chatgpt", "accounts"],
    queryFn: () => api<ChatGptAccount[]>("/chatgpt/accounts"),
  });
  const account = accounts.data?.find((item) => item.id === accountId);
  const login = useQuery({
    queryKey: ["chatgpt", "login", loginId],
    queryFn: () => api<ChatGptLoginAttempt>(`/chatgpt/login/${loginId}`),
    enabled: Boolean(loginId),
    refetchInterval: (result) => (result.state.data?.status === "pending" ? 1500 : false),
    refetchIntervalInBackground: true,
    retry: false,
    staleTime: 0,
  });
  const busy = starting || cancelling || login.data?.status === "pending";
  const models = useQuery({
    queryKey: ["chatgpt", "models", accountId],
    queryFn: () => api<ChatGptModel[]>(`/chatgpt/accounts/${accountId}/models`),
    enabled: Boolean(account?.connected),
    retry: false,
  });
  const signOut = useMutation({
    mutationFn: () =>
      api<{ message: string }>(`/chatgpt/accounts/${accountId}/session`, { method: "DELETE" }),
    onSuccess: (result) => {
      setLoginId(null);
      setLoginError(undefined);
      notify(result.message);
      query.setQueryData<ChatGptAccount[]>(["chatgpt", "accounts"], (saved) =>
        saved?.map((item) => (item.id === accountId ? { ...item, connected: false } : item)),
      );
      query.removeQueries({ queryKey: ["chatgpt", "models", accountId], exact: true });
      void query.invalidateQueries({ queryKey: ["chatgpt", "accounts"] });
    },
  });
  const modelAvailable = models.data?.some((item) => item.id === modelId) ?? false;
  const ready = Boolean(
    account?.connected &&
    models.isSuccess &&
    !models.isFetching &&
    modelAvailable &&
    !busy &&
    !signOut.isPending,
  );

  useEffect(() => {
    onReadyChange(ready);
  }, [onReadyChange, ready]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
      closePopup(popup.current);
      if (activeAttempt.current) {
        void api(`/chatgpt/login/${activeAttempt.current}`, { method: "DELETE" }).catch(() => {});
      }
    };
  }, []);

  useEffect(() => {
    const attempt = login.data;
    if (!attempt || attempt.status === "pending" || handled.current === attempt.id) {
      return;
    }
    handled.current = attempt.id;
    activeAttempt.current = null;
    closePopup(popup.current);
    popup.current = null;
    if (attempt.account) {
      const savedAccount = attempt.account;
      query.setQueryData<ChatGptAccount[]>(["chatgpt", "accounts"], (saved = []) => [
        ...saved.filter((item) => item.id !== savedAccount.id),
        savedAccount,
      ]);
      onAccountChange(savedAccount.id);
      void query.invalidateQueries({ queryKey: ["chatgpt", "models", savedAccount.id] });
    }
    void query.invalidateQueries({ queryKey: ["chatgpt", "accounts"] });
  }, [login.data, onAccountChange, query]);

  async function startLogin(existingAccountId?: string) {
    const loginPopup = window.open("about:blank", "_blank", "popup,width=540,height=720");
    if (!loginPopup) {
      setLoginError("浏览器阻止了登录窗口，请允许弹出窗口后重试。");
      return;
    }
    loginPopup.opener = null;
    loginPopup.document.title = "ChatGPT 登录";
    loginPopup.document.body.textContent = "正在打开 ChatGPT 登录…";
    popup.current = loginPopup;
    const requestGeneration = ++generation.current;
    setStarting(true);
    setLoginId(null);
    setLoginError(undefined);
    try {
      const attempt = await post<ChatGptLoginAttempt>("/chatgpt/login", {
        accountId: existingAccountId,
      });
      if (!mounted.current || requestGeneration !== generation.current) {
        closePopup(loginPopup);
        await api(`/chatgpt/login/${attempt.id}`, { method: "DELETE" }).catch(() => {});
        return;
      }
      activeAttempt.current = attempt.status === "pending" ? attempt.id : null;
      if (attempt.status === "pending" && !attempt.authorizationUrl) {
        throw new Error("登录服务未返回授权地址，请重试。");
      }
      if (attempt.authorizationUrl) {
        loginPopup.location.replace(attempt.authorizationUrl);
      } else {
        closePopup(loginPopup);
      }
      const { authorizationUrl: _authorizationUrl, ...status } = attempt;
      query.setQueryData(["chatgpt", "login", attempt.id], status);
      setLoginId(attempt.id);
    } catch (error) {
      closePopup(loginPopup);
      if (requestGeneration === generation.current && activeAttempt.current) {
        void api(`/chatgpt/login/${activeAttempt.current}`, { method: "DELETE" }).catch(() => {});
        activeAttempt.current = null;
      }
      if (mounted.current && requestGeneration === generation.current) {
        setLoginError(error instanceof Error ? error.message : "无法启动 ChatGPT 登录，请重试。");
      }
    } finally {
      if (mounted.current && requestGeneration === generation.current) {
        setStarting(false);
      }
    }
  }

  async function cancelLogin() {
    generation.current += 1;
    closePopup(popup.current);
    popup.current = null;
    const attemptId = activeAttempt.current;
    activeAttempt.current = null;
    setStarting(false);
    setLoginId(null);
    setLoginError(undefined);
    if (attemptId) {
      setCancelling(true);
      try {
        const attempt = await api<ChatGptLoginAttempt>(`/chatgpt/login/${attemptId}`, {
          method: "DELETE",
        });
        query.setQueryData(["chatgpt", "login", attempt.id], attempt);
        if (mounted.current) {
          setLoginId(attempt.id);
        }
        void query.invalidateQueries({ queryKey: ["chatgpt", "accounts"] });
      } catch (error) {
        if (mounted.current) {
          setLoginError(error instanceof Error ? error.message : "取消登录失败，请重试。");
        }
      } finally {
        if (mounted.current) {
          setCancelling(false);
        }
      }
    }
  }

  return (
    <>
      <Field label="Codex / ChatGPT 账号" id={`${id}-account`} error={accountError}>
        <Select
          {...accountInputProps}
          id={`${id}-account`}
          value={accountId}
          disabled={disabled || busy || signOut.isPending || accounts.isPending}
          searchable
          searchPlaceholder="搜索已保存的账号…"
          onValueChange={(value) => {
            onAccountChange(value);
            setLoginId(null);
            setLoginError(undefined);
            signOut.reset();
          }}
          options={[
            {
              value: "",
              label: accounts.isPending ? "正在读取账号…" : "选择已保存的账号",
            },
            ...(accountId && !account
              ? [{ value: accountId, label: `账号不可用（${accountId.slice(-8)}）` }]
              : []),
            ...(accounts.data?.map((item) => ({
              value: item.id,
              label: `${item.email} · ${item.id.slice(-8)} · ${item.connected ? "已登录" : "需要登录"}`,
            })) ?? []),
          ]}
        />
      </Field>
      {accounts.error && (
        <div className="field">
          <p className="field-error" role="alert">
            {accounts.error.message}
          </p>
          <Button variant="outline" onClick={() => void accounts.refetch()}>
            重新读取账号
          </Button>
        </div>
      )}
      <div className="field">
        <p className="field-hint">
          {account?.connected
            ? "已登录，可使用此账号的 Codex 权益。"
            : "在 OpenAI 官方页面登录，使用账号的 Codex 权益。"}
        </p>
        <div className="flex flex-wrap gap-2">
          {(!account?.connected || busy) && (
            <Button
              disabled={disabled || busy || signOut.isPending}
              onClick={() => void startLogin(accountId || undefined)}
            >
              {busy && <LoaderCircle className="animate-spin" />}
              Continue with ChatGPT
            </Button>
          )}
          {accountId && !busy && (
            <Button
              variant="outline"
              disabled={disabled || signOut.isPending}
              onClick={() => void startLogin()}
            >
              登录其他账号
            </Button>
          )}
          {account?.connected && !busy && (
            <Button
              variant="outline"
              disabled={disabled || signOut.isPending}
              onClick={() => void startLogin(accountId)}
            >
              重新登录
            </Button>
          )}
          {busy && (
            <Button variant="outline" disabled={cancelling} onClick={() => void cancelLogin()}>
              取消登录
            </Button>
          )}
          {account?.connected && !busy && (
            <Button
              variant="outline"
              disabled={disabled || signOut.isPending}
              onClick={() => signOut.mutate()}
            >
              {signOut.isPending && <LoaderCircle className="animate-spin" />}
              退出账号
            </Button>
          )}
          <ButtonLink href="https://chatgpt.com/settings/usage" target="_blank" rel="noreferrer">
            查看用量
            <ExternalLink />
          </ButtonLink>
        </div>
        {busy && (
          <p className="field-hint" role="status">
            {starting
              ? "正在准备登录…"
              : cancelling
                ? "正在取消登录…"
                : "请在登录窗口完成授权，完成后会自动更新。"}
          </p>
        )}
        {login.data?.status === "completed" && (
          <p className="field-hint" role="status">
            账号登录成功，请选择模型后保存连接。
          </p>
        )}
        {login.data?.status === "cancelled" && (
          <p className="field-hint" role="status">
            登录已取消，可以重新登录。
          </p>
        )}
        {(loginError || login.error || login.data?.status === "failed" || signOut.error) && (
          <p className="field-error" role="alert">
            {loginError ||
              login.error?.message ||
              signOut.error?.message ||
              login.data?.error ||
              "登录失败，请重新登录。"}
          </p>
        )}
      </div>
      {account?.connected && (
        <Field
          label="模型"
          id={`${id}-model`}
          error={modelError}
          hint="模型列表来自当前账号，请选择用于代码审查的模型。"
        >
          <Select
            {...modelInputProps}
            id={`${id}-model`}
            value={modelId}
            disabled={disabled || busy || models.isPending || Boolean(models.error)}
            onValueChange={onModelChange}
            searchable
            searchPlaceholder="搜索账号模型…"
            options={[
              {
                value: "",
                label: models.isPending ? "正在获取模型…" : "请选择模型",
              },
              ...(modelId && !modelAvailable
                ? [{ value: modelId, label: `${modelId}（当前不可用）` }]
                : []),
              ...(models.data?.map((item) => ({ value: item.id, label: item.name })) ?? []),
            ]}
          />
          {models.error ? (
            <>
              <p className="field-error" role="alert">
                {models.error.message}
              </p>
              <Button
                variant="outline"
                disabled={models.isFetching}
                onClick={() => void models.refetch()}
              >
                重新获取模型
              </Button>
            </>
          ) : models.isSuccess && models.data.length === 0 ? (
            <p className="field-error" role="alert">
              当前账号没有可用模型，请检查 Codex 权益后重新登录。
            </p>
          ) : modelId && models.isSuccess && !modelAvailable ? (
            <p className="field-error" role="alert">
              此模型已不可用，请重新选择。
            </p>
          ) : null}
        </Field>
      )}
    </>
  );
}
