"use client";
import { useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { message } from "./admin";

const FLOW = "poketto:google-flow";

/**
 * Shows why a Google flow failed. The sign-in and account security cards show it in place; the
 * global instance covers other pages and only answers a flow this browser tab started, so a
 * crafted link cannot raise the warning on its own.
 */
export function GoogleReturnNotice({ global = false }: { global?: boolean }) {
  const [error, setError] = useState("");
  useEffect(() => {
    const url = new URL(window.location.href);
    const reason = url.searchParams.get("loginError");
    if (!reason) return;
    if (global) {
      if (["/admin", "/connect"].includes(url.pathname)) return;
      let started = false;
      try {
        started = sessionStorage.getItem(FLOW) !== null;
      } catch {
        started = false;
      }
      if (!started) return;
    }
    try {
      sessionStorage.removeItem(FLOW);
    } catch {
      // The flag only gates the global notice.
    }
    setError(
      reason === "google_email_in_use"
        ? "该 Google 邮箱已对应一个账号。请使用原有方式登录，再到“登录与安全”绑定 Google。"
        : reason === "google_cancelled"
          ? "Google 授权已取消，你可以重新尝试。"
          : "Google 登录或绑定未能完成，请重新发起授权。",
    );
    url.searchParams.delete("loginError");
    window.history.replaceState(
      window.history.state,
      "",
      url.pathname + url.search + url.hash,
    );
  }, []);
  return error ? (
    <p role="alert" className="notice danger">
      {error}
    </p>
  ) : null;
}

export function GoogleLogin({
  link = false,
  disabled = false,
}: {
  link?: boolean;
  disabled?: boolean;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function start() {
    setPending(true);
    setError("");
    try {
      const returnTo = new URL(window.location.href);
      returnTo.searchParams.delete("loginError");
      if (link) returnTo.searchParams.set("security", "1");
      const result = await api<{ url: string }>(
        "/api/auth/identity/google/start",
        {
          method: "POST",
          body: {
            mode: link ? "LINK" : "LOGIN",
            returnTo: returnTo.pathname + returnTo.search,
          },
        },
      );
      try {
        sessionStorage.setItem(FLOW, "1");
      } catch {
        // Without the flag a failure is still shown on the account and connection pages.
      }
      window.location.assign(result.url);
    } catch (error) {
      setError(message(error));
      setPending(false);
    }
  }
  return (
    <div className="login-options">
      <button
        type="button"
        disabled={disabled || pending}
        onClick={() => void start()}
      >
        {pending
          ? "正在前往 Google…"
          : link
            ? "绑定 Google"
            : "使用 Google 登录"}
      </button>
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </div>
  );
}
