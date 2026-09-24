"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../lib/browser-api";
import {
  communityRoot,
  communityMessage,
  publicCommunityRoot,
  type SpaceParticipation,
} from "../lib/community";
import { LoginDialog } from "./login-dialog";
import { Icon } from "./ui/icons";

type Mine = { id: string; status: string; createdAt: string };

const outcome: Record<string, string> = {
  ACCEPTED: "你上次的修改建议已被采纳，谢谢。",
  DECLINED: "你上次的修改建议没有被采纳。",
  STALE: "你上次的修改建议提交后文章又改过，已过期。",
  WITHDRAWN: "你撤回了上次的修改建议。",
};

/** The digest the server compares against the served body, over the exact UTF-8 bytes. */
export async function bodyDigest(body: string) {
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(body),
  );
  return (
    "sha256:" +
    [...new Uint8Array(bytes)]
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("")
  );
}

/** Lets a signed-in reader propose a corrected body; the author reviews it before anything changes. */
export function CorrectionProposal({
  space,
  route,
  body,
}: {
  space: string;
  route: string;
  body: string;
}) {
  const base = `${communityRoot}/spaces/${encodeURIComponent(space)}/corrections`;
  const [state, setState] = useState<SpaceParticipation | null>(null);
  const [mine, setMine] = useState<Mine | null>(null);
  const [draft, setDraft] = useState(body);
  const [reason, setReason] = useState("");
  const [credited, setCredited] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const value = await api<SpaceParticipation>(
          `${publicCommunityRoot}/spaces/${encodeURIComponent(space)}`,
        );
        if (!active) return;
        setState(value);
        if (!value.accountId) return;
        // An unreadable earlier proposal only hides its status, never the entrance.
        const latest = await api<Mine | undefined>(
          `${base}/mine?` + new URLSearchParams({ route }),
        ).catch(() => undefined);
        if (active) setMine(latest ?? null);
      } catch {
        if (active) setState(null);
      }
    })();
    return () => {
      active = false;
    };
  }, [space, route, version]);
  if (!state) return null;
  if (!state.accountId)
    return (
      <div className="correction-entry">
        <span className="muted">发现错字或不准确的地方？</span>
        <LoginDialog
          label="登录后建议修改"
          className="btn btn-ghost btn-sm"
          onLogin={async () => setVersion((value) => value + 1)}
        />
      </div>
    );
  if (!state.mayParticipate) return null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      // Browsers hand textarea text back with LF; keep the article's own line endings.
      const proposed = body.includes("\r\n")
        ? draft.replace(/\r?\n/g, "\r\n")
        : draft;
      await api(base, {
        method: "POST",
        body: {
          route,
          baseDigest: await bodyDigest(body),
          body: proposed,
          reason,
          credited,
        },
      });
      dialog.current?.close();
      setNotice("修改建议已提交，作者处理后你会收到通知。");
      setVersion((value) => value + 1);
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.code === "COMMUNITY_BASE_CHANGED"
          ? "文章刚刚更新过。请刷新页面，在最新内容上重新修改。"
          : failure instanceof ApiError &&
              failure.code === "COMMUNITY_REQUEST_CONFLICT"
            ? "你对这篇文章已有一条待处理的建议，请等作者处理或先撤回。"
            : communityMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  async function withdraw() {
    if (!mine || busy) return;
    setBusy(true);
    setError("");
    try {
      await api(`${communityRoot}/corrections/${encodeURIComponent(mine.id)}`, {
        method: "DELETE",
      });
      setNotice("");
      setVersion((value) => value + 1);
    } catch (failure) {
      setError(communityMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="correction-entry">
      {mine?.status === "OPEN" || mine?.status === "ACCEPTING" ? (
        <>
          <span className="muted">
            {notice ||
              (mine.status === "OPEN"
                ? "你的修改建议正在等待作者处理。"
                : "作者正在采纳你的修改建议。")}
          </span>
          {mine.status === "OPEN" && (
            <button
              className="btn btn-ghost btn-sm"
              disabled={busy}
              onClick={() => void withdraw()}
            >
              撤回建议
            </button>
          )}
        </>
      ) : (
        <>
          <span className="muted">
            {(mine && outcome[mine.status]) || "发现错字或不准确的地方？"}
          </span>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              setDraft(body);
              setError("");
              dialog.current?.showModal();
            }}
          >
            <Icon name="pen" />
            建议修改
          </button>
        </>
      )}
      {error && !dialog.current?.open && (
        <span role="alert" className="muted">
          {error}
        </span>
      )}
      <dialog ref={dialog} className="correction-dialog" aria-label="建议修改">
        <form onSubmit={(event) => void submit(event)}>
          <h2>建议修改</h2>
          <p className="muted">
            直接改下面的正文，作者会看到逐行对比，采纳后才会改动文章。
          </p>
          <textarea
            value={draft}
            rows={16}
            spellCheck={false}
            aria-label="修改后的正文"
            onChange={(event) => setDraft(event.target.value)}
          />
          <label>
            修改说明（可选）
            <input
              value={reason}
              maxLength={500}
              placeholder="例如：第二段的年份应为 2019"
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <label className="correction-credit">
            <input
              type="checkbox"
              checked={credited}
              onChange={(event) => setCredited(event.target.checked)}
            />
            采纳后在文章底部公开致谢，显示你的昵称
          </label>
          {error && (
            <p className="notice danger" role="alert">
              {error}
            </p>
          )}
          <div className="dialog-actions">
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => dialog.current?.close()}
            >
              取消
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={
                busy ||
                draft.replace(/\r\n/g, "\n") === body.replace(/\r\n/g, "\n")
              }
            >
              {busy ? "正在提交…" : "提交建议"}
            </button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
