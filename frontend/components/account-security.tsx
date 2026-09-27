"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, message } from "../lib/browser-api";
import { GoogleLogin, GoogleReturnNotice } from "./google-login";
import {
  EmailChallenge,
  EmailVerification,
  emailProof,
} from "./email-verification";

type Profile = {
  displayName: string;
  email: string | null;
  passwordEnabled: boolean;
  googleEmail: string | null;
};
export function AccountSecurity({
  onDisplayName,
}: {
  onDisplayName?: (name: string) => void;
}) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [available, setAvailable] = useState(false);
  const [googleAvailable, setGoogleAvailable] = useState(false);
  const [pending, setPending] = useState(false);
  const [binding, setBinding] = useState(false);
  const [challenge, setChallenge] = useState<EmailChallenge | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  async function load() {
    setError("");
    try {
      const [account, policy] = await Promise.all([
        api<Profile>("/api/auth/identity/account"),
        api<{ emailAvailable: boolean; googleAvailable: boolean }>(
          "/api/auth/identity/policy",
        ),
      ]);
      setProfile(account);
      setAvailable(policy.emailAvailable);
      setGoogleAvailable(policy.googleAvailable);
    } catch (error) {
      setError(message(error));
    }
  }
  useEffect(() => {
    void load();
  }, []);
  async function unlinkGoogle() {
    setPending(true);
    setError("");
    setNotice("");
    try {
      setProfile(
        await api<Profile>("/api/auth/identity/google", { method: "DELETE" }),
      );
      setNotice("Google 绑定已解除，请继续使用邮箱或原用户名和密码登录。");
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function save(event: FormEvent<HTMLFormElement>, changeEmail: boolean) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    setNotice("");
    try {
      const saved = await api<Profile>(
        `/api/auth/identity/${changeEmail ? "email" : "account"}`,
        {
          method: "PUT",
          body: changeEmail
            ? emailProof(form, challenge)
            : { displayName: String(form.get("displayName")) },
        },
      );
      setProfile(saved);
      onDisplayName?.(saved.displayName);
      if (changeEmail) {
        setBinding(false);
        setChallenge(null);
      }
      setNotice(
        changeEmail
          ? "邮箱已验证并绑定，原有账号和空间权限保留。"
          : "昵称已保存。",
      );
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="sub-panel">
      <h2>登录与安全</h2>
      <GoogleReturnNotice />
      {error && (
        <p role="alert" className="notice danger">
          {error}{" "}
          {!profile && (
            <button className="text-button" onClick={() => void load()}>
              重新读取
            </button>
          )}
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      {!profile && !error && <p role="status">正在读取账号…</p>}
      {profile && (
        <>
          <form onSubmit={(event) => void save(event, false)}>
            <label>
              昵称
              <input
                name="displayName"
                defaultValue={profile.displayName}
                required
                maxLength={120}
                disabled={pending}
                autoComplete="nickname"
              />
            </label>
            <button disabled={pending}>保存昵称</button>
          </form>
          <p>邮箱：{profile.email ?? "尚未绑定"}</p>
          <p>密码登录：{profile.passwordEnabled ? "已设置" : "尚未设置"}</p>
          <p>Google：{profile.googleEmail ?? "尚未绑定"}</p>
          {googleAvailable && !profile.googleEmail && (
            <GoogleLogin link disabled={pending} />
          )}
          {profile.googleEmail && (
            <>
              <button
                className="text-button"
                disabled={pending || !profile.passwordEnabled}
                onClick={() => void unlinkGoogle()}
              >
                解除 Google 绑定
              </button>
              {!profile.passwordEnabled && (
                <p className="muted">
                  Google
                  是你唯一的登录方式。请先通过绑定邮箱设置密码，再解除绑定。
                </p>
              )}
            </>
          )}
          {!profile.email && (
            <p className="muted">
              绑定并验证邮箱后，可以使用邮箱登录和找回密码。原用户名仍可登录。
            </p>
          )}
          {profile.email && (
            <p className="muted">
              若需设置或更换密码，请退出登录，在登录页选择“忘记密码”。
            </p>
          )}
          {available && !binding && (
            <button
              className="text-button"
              disabled={pending}
              onClick={() => {
                setBinding(true);
                setNotice("");
              }}
            >
              {profile.email ? "更换邮箱" : "绑定邮箱"}
            </button>
          )}
          {binding && (
            <form onSubmit={(event) => void save(event, true)}>
              <EmailVerification
                purpose="email"
                pending={pending}
                onChallenge={setChallenge}
              />
              <button disabled={pending}>
                {pending ? "正在保存…" : "验证并绑定"}
              </button>
              <button
                type="button"
                className="text-button"
                disabled={pending}
                onClick={() => {
                  setBinding(false);
                  setChallenge(null);
                }}
              >
                取消
              </button>
            </form>
          )}
        </>
      )}
    </section>
  );
}
