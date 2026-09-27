"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { communityMessage, type CommunityProfile } from "../lib/community";
import { articleHref, date } from "../lib/format";
import { sourceDifference } from "../lib/source-diff";
import { useConfirmation } from "./confirmation";
import { SourceDiff } from "./source-diff";

type Review = {
  id: string;
  position: number;
  space: string;
  route: string;
  title: string;
  author: CommunityProfile | null;
  reason: string;
  proposedBody: string;
  currentBody: string;
  stale: boolean;
  createdAt: string;
};
type Page = { items: Review[]; nextBefore: number | null };

/** Open reader corrections of one space, for members who may publish; accepting commits the text. */
export function CorrectionReview({ workspaceId }: { workspaceId: string }) {
  const base = `/api/auth/workspaces/${encodeURIComponent(workspaceId)}/corrections`;
  const confirm = useConfirmation();
  const [page, setPage] = useState<Page | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    setError("");
    void api<Page>(base)
      .then((value) => {
        if (active) setPage(value);
      })
      .catch((failure) => {
        if (active) setError(communityMessage(failure));
      });
    return () => {
      active = false;
    };
  }, [base, version]);

  async function resolve(review: Review, accept: boolean) {
    if (busy) return;
    if (
      accept &&
      !review.stale &&
      !(await confirm({
        title: "采纳这条修改建议？",
        description: `会把《${review.title}》的正文替换为建议的文本并提交到仓库，frontmatter 保持不变，提交里会注明建议人。`,
        confirmLabel: "采纳并提交",
      }))
    )
      return;
    setBusy(true);
    setError("");
    setReceipt("");
    try {
      if (accept) {
        const { result } = await api<{
          result: "ACCEPTED" | "ALREADY_APPLIED" | "STALE";
        }>(`${base}/${encodeURIComponent(review.id)}/accept`, {
          method: "POST",
          timeoutMs: 90000,
        });
        setReceipt(
          result === "ACCEPTED"
            ? `已采纳，《${review.title}》的正文已更新。`
            : result === "ALREADY_APPLIED"
              ? `《${review.title}》的正文本来就是建议的内容，已记为采纳，没有产生新的提交。`
              : `《${review.title}》在建议提交后改过，这条建议已标记为过期。`,
        );
      } else {
        await api(`${base}/${encodeURIComponent(review.id)}/decline`, {
          method: "POST",
        });
        setReceipt("已标记为不采纳。");
      }
      setVersion((value) => value + 1);
    } catch (failure) {
      if (
        failure instanceof ApiError &&
        failure.code === "COMMUNITY_REQUEST_CONFLICT"
      ) {
        setError("这条建议刚被处理过，或正在被别人采纳，列表已刷新。");
        setVersion((value) => value + 1);
      } else setError(communityMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="management-panel">
      <section className="sub-panel" aria-label="读者勘误">
        <div className="panel-heading">
          <h2>读者勘误</h2>
        </div>
        <p className="muted">
          读者在公开文章上提的修改建议。采纳后正文会按建议提交到仓库；不采纳不会改动任何内容。
        </p>
        {receipt && (
          <p className="notice success" role="status">
            {receipt}
          </p>
        )}
        {error && (
          <p className="notice danger" role="alert">
            {error}
          </p>
        )}
        {!page && !error && (
          <p role="status" className="muted">
            正在读取修改建议…
          </p>
        )}
        {page && page.items.length === 0 && <p>暂时没有待处理的修改建议。</p>}
        {page?.items.map((review) => {
          const difference = sourceDifference(
            review.currentBody,
            review.proposedBody,
          );
          return (
            <article className="correction-item" key={review.id}>
              <header>
                <a href={articleHref(review.route, review.space)}>
                  {review.title}
                </a>
                <span className="muted">
                  {review.author?.displayName || "账号用户"} ·{" "}
                  {date(review.createdAt)}
                </span>
                {review.stale && <span className="kind">原文已改动</span>}
              </header>
              {review.reason && <p>{review.reason}</p>}
              {review.stale ? (
                <p className="muted">
                  提出这条建议之后文章又改过，不能再直接采纳。标为过期会通知读者基于新内容重新提。
                </p>
              ) : difference.kind === "lines" ? (
                <SourceDiff lines={difference.lines} />
              ) : (
                <div className="history-sources">
                  <section>
                    <h3>当前正文</h3>
                    <pre>{review.currentBody}</pre>
                  </section>
                  <section>
                    <h3>建议的正文</h3>
                    <pre>{review.proposedBody}</pre>
                  </section>
                </div>
              )}
              <div className="correction-actions">
                <button
                  className="button-primary"
                  disabled={busy}
                  onClick={() => void resolve(review, true)}
                >
                  {review.stale ? "标为过期" : "采纳"}
                </button>
                <button
                  disabled={busy}
                  onClick={() => void resolve(review, false)}
                >
                  不采纳
                </button>
              </div>
            </article>
          );
        })}
        {page?.nextBefore && (
          <p className="muted">还有更早的建议，处理完这些后会显示。</p>
        )}
      </section>
    </div>
  );
}
