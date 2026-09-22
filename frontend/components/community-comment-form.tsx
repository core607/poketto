"use client";
import { useState } from "react";
import { api } from "../lib/browser-api";
import { communityMessage } from "../lib/community";

export function CommunityCommentForm({
  endpoint,
  parentId,
  onSaved,
}: {
  endpoint: string;
  parentId: string | null;
  onSaved: () => void;
}) {
  const [body, setBody] = useState("");
  const [attempt, setAttempt] = useState<{ id: string; body: string } | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);
  return (
    <form
      className="community-form"
      onSubmit={async (event) => {
        event.preventDefault();
        setBusy(true);
        setError("");
        setSent(false);
        const request =
          attempt?.body === body ? attempt : { id: crypto.randomUUID(), body };
        setAttempt(request);
        try {
          await api(endpoint, {
            method: "POST",
            body: { requestId: request.id, parentId, body },
          });
          setBody("");
          setAttempt(null);
          setSent(true);
          onSaved();
        } catch (failure) {
          setError(communityMessage(failure));
        } finally {
          setBusy(false);
        }
      }}
    >
      <label>
        {parentId ? "回复内容" : "留下评论"}
        <textarea
          maxLength={8000}
          required
          value={body}
          disabled={busy}
          onChange={(event) => {
            setBody(event.target.value);
            setSent(false);
          }}
        />
      </label>
      <div className="community-actions">
        <button disabled={busy || !body.trim() || [...body].length > 4000}>
          {busy ? "正在提交…" : parentId ? "发送回复" : "发表评论"}
        </button>
        <small className="muted">{[...body].length} / 4000 · 纯文本</small>
      </div>
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {sent && <p role="status">评论已提交。</p>}
    </form>
  );
}
