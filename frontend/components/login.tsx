"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";
import { GoogleLogin, GoogleReturnNotice } from "./google-login";
import {
  EmailChallenge,
  EmailVerification,
  emailProof,
  NewPasswordFields,
} from "./email-verification";

type Mode = "login" | "signup" | "recovery";
type Policy = { emailAvailable: boolean; googleAvailable: boolean };
export function Login({
  onLogin,
  connection = false,
  embedded = false,
}: {
  onLogin: () => Promise<void>;
  connection?: boolean;
  embedded?: boolean;
}) {
  const [mode, setMode] = useState<Mode>("login");
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [policyError, setPolicyError] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [login, setLogin] = useState("");
  const [challenge, setChallenge] = useState<EmailChallenge | null>(null);
  async function loadPolicy() {
    setPolicyError("");
    try {
      setPolicy(await api<Policy>("/api/auth/identity/policy"));
    } catch (error) {
      setPolicyError(message(error));
    }
  }
  useEffect(() => {
    void loadPolicy();
  }, []);
  function changeMode(next: Mode) {
    setMode(next);
    setChallenge(null);
    setError("");
    setNotice("");
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const password = String(form.get("password"));
      let username = String(form.get("login") ?? "").trim();
      if (mode !== "login") {
        if (password !== String(form.get("confirmation")))
          throw new ApiError(400, "两次密码不一致，请重新确认。");
        const proof = emailProof(form, challenge);
        await api(`/api/auth/identity/${mode}`, {
          method: "POST",
          body: {
            proof,
            password,
            ...(mode === "signup"
              ? { displayName: String(form.get("displayName")) }
              : {}),
          },
        });
        username = proof.email;
        setLogin(username);
        setMode("login");
        setChallenge(null);
        setNotice(
          mode === "signup"
            ? "账号已创建。若尚未登录，请使用邮箱和新密码登录。"
            : "密码已更新，旧会话和旧连接密钥已失效。请用新密码登录。空间权限保留。",
        );
        if (mode === "recovery") return;
      }
      await api("/api/auth/login", {
        method: "POST",
        form: new URLSearchParams({ username, password }),
      });
      await onLogin();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <section className={embedded ? "auth embedded" : "auth"}>
      <GoogleReturnNotice />
      <header className="auth-head">
        <p className="eyebrow">
          {mode === "login"
            ? "欢迎回来"
            : mode === "signup"
              ? "开始记录与收藏"
              : "找回账号"}
        </p>
        <h1>
          {mode === "login"
            ? "登录 Poketto"
            : mode === "signup"
              ? "创建账号"
              : "重设密码"}
        </h1>
        <p>
          {mode === "login"
            ? "使用邮箱或原有用户名登录。"
            : mode === "signup"
              ? "验证邮箱即可注册。加入空间需要空间主人的邀请。"
              : "验证账号绑定的邮箱，设置新密码。旧会话和旧连接密钥将失效，空间权限会保留。"}
        </p>
      </header>
      {notice && (
        <p role="status" className="notice success">
          {notice}
        </p>
      )}
      <form onSubmit={submit} key={mode}>
        <fieldset disabled={pending}>
          {mode === "login" ? (
            <>
              <label>
                邮箱或用户名
                <input
                  name="login"
                  defaultValue={login}
                  required
                  autoComplete="username"
                  maxLength={254}
                />
              </label>
              <label>
                密码
                <input
                  name="password"
                  type="password"
                  required
                  autoComplete="current-password"
                  maxLength={256}
                />
              </label>
            </>
          ) : (
            <>
              {mode === "signup" && (
                <label>
                  昵称
                  <input
                    name="displayName"
                    required
                    autoComplete="nickname"
                    maxLength={120}
                  />
                </label>
              )}
              <EmailVerification
                purpose={mode}
                pending={pending}
                onChallenge={setChallenge}
              />
              <NewPasswordFields />
            </>
          )}
          {error && (
            <p className="notice danger" role="alert">
              {error}
            </p>
          )}
          <button className="auth-submit" disabled={pending}>
            {pending
              ? "正在处理…"
              : mode === "login"
                ? "登录"
                : mode === "signup"
                  ? "注册并登录"
                  : "更新密码"}
          </button>
        </fieldset>
      </form>
      {mode === "login" && policy?.googleAvailable && (
        <GoogleLogin disabled={pending} />
      )}
      {policyError && (
        <p role="alert" className="notice danger">
          登录方式读取失败。
          <button className="text-button" onClick={() => void loadPolicy()}>
            重试
          </button>
        </p>
      )}
      <div className="auth-switch">
        {mode !== "login" ? (
          <button
            className="text-button"
            disabled={pending}
            onClick={() => changeMode("login")}
          >
            返回登录
          </button>
        ) : (
          policy?.emailAvailable && (
            <>
              {!connection && (
                <button
                  className="text-button"
                  disabled={pending}
                  onClick={() => changeMode("signup")}
                >
                  注册
                </button>
              )}
              <button
                className="text-button"
                disabled={pending}
                onClick={() => changeMode("recovery")}
              >
                忘记密码
              </button>
            </>
          )
        )}
      </div>
      <p className="auth-legal">
        使用本站前，请阅读
        <a href="/privacy" target="_blank" rel="noopener noreferrer">
          隐私政策
        </a>
        和
        <a href="/terms" target="_blank" rel="noopener noreferrer">
          服务条款
        </a>
        。
      </p>
    </section>
  );
}
