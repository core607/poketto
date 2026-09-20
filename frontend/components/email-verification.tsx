"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";

export type EmailChallenge = {
  challengeId: string;
  email: string;
  expiresAt: string;
};
export function emailProof(form: FormData, challenge: EmailChallenge | null) {
  const email = String(form.get("email") ?? "")
    .trim()
    .toLowerCase();
  if (!challenge || email !== challenge.email)
    throw new ApiError(400, "请先为当前邮箱获取验证码。");
  if (Date.parse(challenge.expiresAt) <= Date.now())
    throw new ApiError(400, "验证码已过期，请重新获取。");
  return {
    challengeId: challenge.challengeId,
    email,
    code: String(form.get("code") ?? ""),
  };
}

export function EmailVerification({
  purpose,
  pending,
  onChallenge,
  defaultEmail = "",
}: {
  purpose: "signup" | "recovery" | "email";
  pending: boolean;
  onChallenge: (challenge: EmailChallenge | null) => void;
  defaultEmail?: string;
}) {
  const [sending, setSending] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const remaining = Math.max(0, Math.ceil((retryAt - now) / 1000));
  useEffect(() => {
    if (retryAt <= now) return;
    const timer = setTimeout(
      () => setNow(Date.now()),
      Math.min(1000, retryAt - now),
    );
    return () => clearTimeout(timer);
  }, [retryAt, now]);
  async function send(button: HTMLButtonElement) {
    const field = button.form?.elements.namedItem(
      "email",
    ) as HTMLInputElement | null;
    if (!field || !field.reportValidity()) return;
    const email = field.value.trim().toLowerCase();
    setSending(true);
    setError("");
    setNotice("");
    onChallenge(null);
    try {
      const receipt = await api<
        Omit<EmailChallenge, "email"> & { retryAfterSeconds: number }
      >(`/api/auth/identity/${purpose}/challenge`, {
        method: "POST",
        body: { email },
      });
      onChallenge({ ...receipt, email });
      const receivedAt = Date.now();
      setNow(receivedAt);
      setRetryAt(receivedAt + receipt.retryAfterSeconds * 1000);
      setNotice(
        purpose === "recovery"
          ? "若该邮箱已绑定账号，你会收到找回密码的验证码。请检查收件箱和垃圾邮件。"
          : "验证码已发送，10 分钟内有效。请检查收件箱和垃圾邮件。",
      );
    } catch (error) {
      setError(message(error));
    } finally {
      setSending(false);
    }
  }
  return (
    <>
      <label>
        邮箱
        <input
          name="email"
          type="email"
          defaultValue={defaultEmail}
          required
          maxLength={254}
          autoComplete="email"
          disabled={pending || sending}
          onChange={() => {
            onChallenge(null);
            setNotice("");
          }}
        />
      </label>
      <button
        type="button"
        disabled={pending || sending || remaining > 0}
        onClick={(event) => void send(event.currentTarget)}
      >
        {sending
          ? "正在发送…"
          : remaining > 0
            ? `${remaining} 秒后可重发`
            : "获取验证码"}
      </button>
      {notice && (
        <p role="status" className="muted">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      <label>
        邮箱验证码
        <input
          name="code"
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="[0-9]{6}"
          minLength={6}
          maxLength={6}
          required
          disabled={pending || sending}
        />
      </label>
    </>
  );
}

export function NewPasswordFields() {
  return (
    <>
      <label>
        新密码
        <input
          name="password"
          type="password"
          required
          autoComplete="new-password"
          minLength={12}
          maxLength={256}
        />
      </label>
      <p className="muted form-help">密码为 12–256 位。</p>
      <label>
        确认密码
        <input
          name="confirmation"
          type="password"
          required
          autoComplete="new-password"
          minLength={12}
          maxLength={256}
        />
      </label>
    </>
  );
}
