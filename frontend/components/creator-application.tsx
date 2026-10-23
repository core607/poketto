"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, message } from "../lib/browser-api";
import { date } from "../lib/format";
import { canCreateSpaces, type SiteGroup } from "../lib/site-groups";
import {
  applicationChanged,
  useCreatorStanding,
  type CreatorApplicationRecord,
} from "../lib/creator-application";

export function CreatorApplication({
  onGroup,
}: {
  onGroup?: (group: SiteGroup) => void;
}) {
  const {
    standing,
    setStanding,
    error: readError,
    refresh,
  } = useCreatorStanding();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (standing) onGroup?.(standing.group);
  }, [standing?.group, onGroup]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const statement = String(
      new FormData(event.currentTarget).get("statement") ?? "",
    );
    setPending(true);
    setError("");
    try {
      const latest = await api<CreatorApplicationRecord>(
        "/api/auth/creator-application",
        { method: "POST", body: { statement } },
      );
      setStanding(
        (current) => current && { ...current, latest, availableAt: null },
      );
      applicationChanged();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  if (!standing)
    return (
      <p role={readError ? "alert" : "status"} className="muted">
        {readError || "正在读取申请状态…"}
        {readError && (
          <button className="text-button" onClick={refresh}>
            重试
          </button>
        )}
      </p>
    );
  const { latest, unread, availableAt, group } = standing;
  const creator = canCreateSpaces(group);
  const waiting = availableAt && new Date(availableAt).getTime() > Date.now();
  return (
    <div className="creator-application">
      {latest && latest.status !== "OPEN" && (
        <Decision application={latest} creator={creator} />
      )}
      {unread && unread.id !== latest?.id && (
        <div>
          <p className="muted">之前的申请还有一条未读结果。</p>
          <Decision application={unread} creator={false} />
        </div>
      )}
      {creator && (!latest || latest.status !== "APPROVED") && (
        <p>
          你已具备创作者资格。<a href="/admin?tab=spaces">创建自己的空间</a>
        </p>
      )}
      {latest?.status === "OPEN" && (
        <p className="notice" role="status">
          你在 {date(latest.createdAt)} 提交的申请正在等待站点管理员审核。
          <button type="button" className="text-button" onClick={refresh}>
            刷新申请状态
          </button>
        </p>
      )}
      {!creator && waiting && <p>{date(availableAt)} 之后可以再次申请。</p>}
      {!creator && latest?.status !== "OPEN" && !waiting && (
        <form onSubmit={submit} className="stack-form">
          <label>
            说说你想用空间做什么
            <textarea
              name="statement"
              required
              maxLength={1000}
              rows={4}
              placeholder="例如：整理读书笔记并公开成一个小网站。"
              disabled={pending}
            />
          </label>
          <div className="button-row">
            <button disabled={pending}>
              {pending ? "正在提交…" : "申请成为创作者"}
            </button>
          </div>
        </form>
      )}
      {(error || readError) && (
        <p role="alert" className="notice danger">
          {error || readError}
        </p>
      )}
    </div>
  );
}

function Decision({
  application,
  creator,
}: {
  application: CreatorApplicationRecord;
  creator: boolean;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function acknowledge() {
    setPending(true);
    setError("");
    try {
      await api(`/api/auth/creator-application/${application.id}/read`, {
        method: "POST",
      });
      applicationChanged();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="notice" role="status">
      <p>
        {application.status === "APPROVED"
          ? "创作者申请已通过"
          : "创作者申请未通过"}
        {application.decisionNote ? `：${application.decisionNote}` : "。"}
      </p>
      {application.decidedAt && (
        <p className="muted">{date(application.decidedAt)}</p>
      )}
      <div className="button-row">
        {creator && (
          <a className="btn btn-primary" href="/admin?tab=spaces">
            创建自己的空间
          </a>
        )}
        {!application.read && (
          <button
            type="button"
            className="text-button"
            disabled={pending}
            onClick={() => void acknowledge()}
          >
            {pending ? "正在标记…" : "知道了"}
          </button>
        )}
      </div>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}

/** Decisions stay in the account's own durable application record, separate from article notifications. */
export function CreatorDecisionNotice({
  compact = false,
}: {
  compact?: boolean;
}) {
  const { standing } = useCreatorStanding();
  const latest = standing?.unread;
  if (!standing || !latest || latest.status === "OPEN" || latest.read)
    return null;
  if (compact)
    return (
      <a href="/account#creator">
        {latest.status === "APPROVED"
          ? "创作者申请已通过"
          : "创作者申请有新结果"}
      </a>
    );
  return (
    <Decision application={latest} creator={canCreateSpaces(standing.group)} />
  );
}
