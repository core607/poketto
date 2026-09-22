"use client";
import { useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { date } from "../lib/format";
import {
  communityRoot,
  publicCommunityRoot,
  communityMessage,
  type ArticleThread,
  type CommunityComment,
  type CommunityPage,
} from "../lib/community";
import { CommunityCommentForm } from "./community-comment-form";

type Props = {
  comment: CommunityComment;
  path: string;
  thread: ArticleThread;
  busy: boolean;
  version: number;
  act: (operation: () => Promise<unknown>) => Promise<void>;
  refresh: () => void;
};
export function CommunityCommentItem({
  comment,
  path,
  thread,
  busy,
  version,
  act,
  refresh,
}: Props) {
  const [replies, setReplies] =
    useState<CommunityPage<CommunityComment> | null>(null);
  const [repliesOpen, setRepliesOpen] = useState(false);
  const [before, setBefore] = useState(0);
  const [replying, setReplying] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmBlock, setConfirmBlock] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const moderating =
    thread.mayModerate &&
    (comment.deleted || thread.accountId !== comment.author?.accountId);
  useEffect(() => {
    if (!repliesOpen) return;
    let active = true;
    setLoading(true);
    setError("");
    void api<ArticleThread>(
      `${publicCommunityRoot}${path}?parentId=${encodeURIComponent(comment.id)}&before=${before}`,
    )
      .then((next) => {
        if (active) setReplies(next.comments);
      })
      .catch((failure) => {
        if (active) {
          setError(communityMessage(failure));
          setReplies(null);
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [repliesOpen, comment.id, path, before, version]);
  return (
    <div
      className={
        comment.parentId
          ? "community-comment community-reply"
          : "community-comment"
      }
      id={`comment-${comment.id}`}
    >
      <div className="community-comment-meta">
        <strong>
          {comment.deleted
            ? "评论已删除"
            : comment.author?.displayName || "账号用户"}
        </strong>
        {comment.author && (
          <span title={`账号标识：${comment.author.accountId}`}>站内账号</span>
        )}
        <time dateTime={comment.createdAt}>{date(comment.createdAt)}</time>
      </div>
      {!comment.deleted && (
        <p className="community-comment-body">{comment.body}</p>
      )}
      <div className="community-actions">
        {!comment.parentId && !comment.deleted && thread.mayParticipate && (
          <button
            className="text-button"
            disabled={busy}
            onClick={() => setReplying(!replying)}
          >
            回复
          </button>
        )}
        {!comment.parentId && comment.replies > 0 && (
          <button
            className="text-button"
            disabled={loading || busy}
            onClick={() => setRepliesOpen(!repliesOpen)}
          >
            {loading
              ? "正在读取…"
              : repliesOpen
                ? "收起回复"
                : `${comment.replies} 条回复`}
          </button>
        )}
        {comment.mayDelete && (!comment.deleted || thread.mayModerate) && (
          <button
            className="text-button"
            disabled={busy}
            onClick={() => setConfirmDelete(!confirmDelete)}
          >
            {moderating ? (comment.parentId ? "移除评论" : "移除讨论") : "删除"}
          </button>
        )}
        {thread.accountId && !comment.deleted && (
          <button
            className="text-button"
            disabled={busy}
            onClick={() => setReporting(!reporting)}
          >
            举报
          </button>
        )}
        {thread.accountId &&
          comment.author &&
          comment.author.accountId !== thread.accountId && (
            <button
              className="text-button"
              disabled={busy}
              onClick={() => setConfirmBlock(!confirmBlock)}
            >
              屏蔽账号
            </button>
          )}
      </div>
      {confirmDelete && (
        <div className="notice">
          <p>
            {thread.accountId === comment.author?.accountId
              ? "删除后不能恢复，已有回复会保留。"
              : "移除后，这条评论及其回复将不再公开显示。"}
          </p>
          <button
            disabled={busy}
            onClick={() =>
              void act(async () => {
                await api(
                  `${communityRoot}/comments/${comment.id}${moderating ? "/moderation" : ""}`,
                  {
                    method: "DELETE",
                  },
                );
                setConfirmDelete(false);
              })
            }
          >
            确认删除
          </button>
          <button
            className="text-button"
            onClick={() => setConfirmDelete(false)}
          >
            取消
          </button>
        </div>
      )}
      {confirmBlock && comment.author && (
        <div className="notice">
          <p>
            屏蔽后，你将不再看到此账号的评论与通知，双方不能新增相互回复。公开文章仍可被匿名阅读。
          </p>
          <button
            disabled={busy}
            onClick={() =>
              void act(() =>
                api(`${communityRoot}/blocks/${comment.author!.accountId}`, {
                  method: "PUT",
                  body: { enabled: true },
                }),
              )
            }
          >
            确认屏蔽
          </button>
          <button
            className="text-button"
            onClick={() => setConfirmBlock(false)}
          >
            取消
          </button>
        </div>
      )}
      {reporting && (
        <form
          className="community-form"
          onSubmit={(event) => {
            event.preventDefault();
            const reason = String(
              new FormData(event.currentTarget).get("reason") ?? "",
            );
            if ([...reason].length > 1000) {
              setError("举报原因最多 1,000 个字符。");
              return;
            }
            void act(async () => {
              await api(`${communityRoot}/comments/${comment.id}/reports`, {
                method: "POST",
                body: { reason },
              });
              setReporting(false);
            });
          }}
        >
          <label>
            举报原因
            <textarea name="reason" required maxLength={2000} disabled={busy} />
          </label>
          <button disabled={busy}>提交举报</button>
        </form>
      )}
      {replying && !comment.deleted && (
        <CommunityCommentForm
          endpoint={`${communityRoot}${path}/comments`}
          parentId={comment.id}
          onSaved={() => {
            setReplying(false);
            setRepliesOpen(true);
            refresh();
          }}
        />
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {repliesOpen && replies && (
        <div>
          {replies.items.map((reply) => (
            <CommunityCommentItem
              key={reply.id}
              comment={reply}
              path={path}
              thread={thread}
              busy={busy}
              act={act}
              refresh={refresh}
              version={version}
            />
          ))}
          <div className="community-actions">
            {before > 0 && (
              <button className="text-button" onClick={() => setBefore(0)}>
                最新评论回复
              </button>
            )}
            {replies.nextBefore !== null && (
              <button
                className="text-button"
                disabled={loading}
                onClick={() => setBefore(replies.nextBefore!)}
              >
                更早的回复
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
