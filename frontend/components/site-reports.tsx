"use client";
import { FormEvent, useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import {
  communityMessage,
  communityRoot,
  type CommunityPage,
  type CommunityReport,
} from "../lib/community";
import { articleHref, date } from "../lib/format";
import {
  navigateSite,
  siteChoice,
  useSiteLocation,
} from "../lib/site-location";

const statuses = { OPEN: "待处理", DISMISSED: "保留评论", REMOVED: "已移除" };
type Status = keyof typeof statuses;

export function ReportQueue() {
  const location = useSiteLocation();
  const status = siteChoice(
    location,
    "reportStatus",
    Object.keys(statuses) as Status[],
    "OPEN",
  );
  const cursor = Number(location.get("reportBefore") ?? 0);
  const before = Number.isSafeInteger(cursor) && cursor > 0 ? cursor : 0;
  const [page, setPage] = useState<CommunityPage<CommunityReport> | null>(null);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [version, setVersion] = useState(0);
  const refresh = () => setVersion((value) => value + 1);
  useEffect(() => {
    let active = true;
    setPage(null);
    setError("");
    api<CommunityPage<CommunityReport>>(
      `${communityRoot}/reports?status=${status}&before=${before}`,
    )
      .then((value) => {
        if (active) setPage(value);
      })
      .catch((failure) => {
        if (active) setError(communityMessage(failure));
      });
    return () => {
      active = false;
    };
  }, [status, before, version]);
  return (
    <>
      <div className="filter-chips" role="group" aria-label="举报状态">
        {(Object.keys(statuses) as Status[]).map((key) => (
          <button
            key={key}
            type="button"
            aria-pressed={key === status}
            onClick={() => {
              setReceipt("");
              navigateSite({ reportStatus: key, reportBefore: 0 });
            }}
          >
            {statuses[key]}
          </button>
        ))}
      </div>
      {receipt && (
        <p role="status" className="notice success">
          {receipt}
        </p>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {!page && !error && <p role="status">正在读取举报…</p>}
      {page?.items.length === 0 && (
        <p className="muted">没有{statuses[status]}的举报。</p>
      )}
      {page?.items.map((report) => (
        <ReportItem
          key={report.position}
          report={report}
          onResolved={() => {
            setReceipt("举报已处理，结果已记入处理记录。");
            refresh();
          }}
        />
      ))}
      <div className="button-row">
        {before > 0 && (
          <button
            type="button"
            onClick={() => navigateSite({ reportBefore: 0 })}
          >
            返回最新
          </button>
        )}
        {page?.nextBefore != null && (
          <button
            type="button"
            onClick={() => navigateSite({ reportBefore: page.nextBefore })}
          >
            更早记录
          </button>
        )}
        <button type="button" className="text-button" onClick={refresh}>
          刷新举报
        </button>
      </div>
    </>
  );
}

function ReportItem({
  report,
  onResolved,
}: {
  report: CommunityReport;
  onResolved: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const decision = String(form.get("decision") ?? "");
    const note = String(form.get("note") ?? "").trim();
    if (!["keep", "remove"].includes(decision) || !note) {
      setError("请选择处理结果，并写明处理理由。");
      return;
    }
    setPending(true);
    setError("");
    try {
      await api(`${communityRoot}/reports/${report.position}/resolve`, {
        method: "POST",
        body: { remove: decision === "remove", note },
      });
      onResolved();
    } catch (failure) {
      setError(communityMessage(failure));
    } finally {
      setPending(false);
    }
  }
  return (
    <article className="sub-panel report-item">
      <header className="panel-heading">
        <h3>举报 #{report.position}</h3>
        <span>{statuses[report.status]}</span>
      </header>
      <p className="muted">
        {report.reporter?.displayName || "账号用户"} · {date(report.createdAt)}
      </p>
      <p>举报原因：{report.reason}</p>
      {report.article ? (
        <p>
          <a
            href={
              articleHref(report.article.route, report.article.space) +
              "#discussion"
            }
            target="_blank"
            rel="noopener noreferrer"
          >
            {report.article.spaceName} · {report.article.title}
          </a>
        </p>
      ) : (
        <p className="muted">文章当前无法公开访问。</p>
      )}
      <a
        href={`/site?section=spaces&workspace=${encodeURIComponent(report.workspaceId)}`}
      >
        审阅空间
      </a>
      {report.parentBody !== null && (
        <details>
          <summary>被回复的评论</summary>
          <blockquote className="community-comment-body">
            {report.parentAuthor?.displayName || "账号用户"}：
            {report.parentBody || "评论已删除"}
          </blockquote>
        </details>
      )}
      <blockquote className="community-comment-body">
        {report.author?.displayName || "账号用户"}：
        {report.commentBody || "评论已删除"}
      </blockquote>
      {report.status === "OPEN" ? (
        <form className="stack-form" onSubmit={submit}>
          <label>
            处理结果
            <select name="decision" required defaultValue="" disabled={pending}>
              <option value="" disabled>
                选择处理结果
              </option>
              <option value="keep">保留评论</option>
              <option value="remove">移除评论及其回复</option>
            </select>
          </label>
          <label>
            处理理由
            <textarea
              name="note"
              required
              maxLength={500}
              rows={2}
              disabled={pending}
            />
          </label>
          <p className="form-help">
            移除后，评论及其回复不再公开显示。处理结果和理由仅站点管理员可见。
          </p>
          <button disabled={pending}>
            {pending ? "正在处理…" : "提交处理"}
          </button>
        </form>
      ) : (
        <div className="notice">
          <p>
            {report.reviewer?.displayName || "未记录处理人"}
            {report.reviewedAt ? ` · ${date(report.reviewedAt)}` : ""}
          </p>
          <p>{report.reviewNote || "未记录处理理由。"}</p>
        </div>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </article>
  );
}
