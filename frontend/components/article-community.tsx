"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import {
  communityRoot,
  publicCommunityRoot,
  communityMessage,
  type ArticleThread,
  type SpaceParticipation,
} from "../lib/community";
import { CommunityCommentForm } from "./community-comment-form";
import { CommunityCommentItem } from "./community-comment";
import { Login } from "./login";

export function SpaceFollow({ space }: { space: string }) {
  const [state, setState] = useState<SpaceParticipation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    void api<SpaceParticipation>(
      `${publicCommunityRoot}/spaces/${encodeURIComponent(space)}`,
    )
      .then((value) => {
        if (active) setState(value);
      })
      .catch(() => {
        if (active) setState(null);
      });
    return () => {
      active = false;
    };
  }, [space]);
  if (!state) return null;
  return (
    <div className="community-follow">
      <button
        className="button-secondary"
        disabled={busy || (!state.mayParticipate && !state.following)}
        aria-pressed={state.following}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            await api(
              `${communityRoot}/spaces/${encodeURIComponent(space)}/following`,
              { method: "PUT", body: { enabled: !state.following } },
            );
            setState({ ...state, following: !state.following });
          } catch (failure) {
            setError(communityMessage(failure));
          } finally {
            setBusy(false);
          }
        }}
      >
        {state.following ? "已关注 · 取消关注" : "关注这个空间"}
      </button>
      {!state.accountId ? (
        <details>
          <summary>登录后关注空间</summary>
          <Login
            onLogin={async () =>
              setState(
                await api<SpaceParticipation>(
                  `${publicCommunityRoot}/spaces/${encodeURIComponent(space)}`,
                ),
              )
            }
          />
        </details>
      ) : (
        !state.mayParticipate &&
        !state.following && (
          <small className="muted">社区成员及以上可关注空间。</small>
        )
      )}
      {error && <span role="alert">{error}</span>}
    </div>
  );
}

export function ArticleCommunity({
  space,
  articleId,
}: {
  space: string;
  articleId: string | null;
}) {
  if (!articleId)
    return (
      <section className="community-section">
        <SpaceFollow space={space} />
        <p className="muted">这篇文章尚未启用互动。</p>
      </section>
    );
  return (
    <ArticleDiscussion
      key={`${space}/${articleId}`}
      space={space}
      articleId={articleId}
    />
  );
}

function ArticleDiscussion({
  space,
  articleId,
}: {
  space: string;
  articleId: string;
}) {
  const path = `/spaces/${encodeURIComponent(space)}/articles/${encodeURIComponent(articleId)}`;
  const [thread, setThread] = useState<ArticleThread | null>(null);
  const [before, setBefore] = useState(0);
  const [busy, setBusy] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const refresh = () => setVersion((value) => value + 1);
  useEffect(() => {
    let active = true;
    setError("");
    void api<ArticleThread>(`${publicCommunityRoot}${path}?before=${before}`)
      .then((value) => {
        if (active) {
          setThread(value);
          setUnavailable(false);
        }
      })
      .catch((failure) => {
        if (!active) return;
        if (failure instanceof ApiError && failure.status === 404) {
          setUnavailable(true);
          setThread(null);
        } else setError(communityMessage(failure));
      });
    return () => {
      active = false;
    };
  }, [path, before, version]);
  async function act(operation: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await operation();
      refresh();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) {
        setUnavailable(true);
        setThread(null);
      } else setError(communityMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  if (unavailable) return null;
  return (
    <section
      className="community-section"
      aria-label="文章互动"
      id="discussion"
    >
      <div className="community-heading">
        <h2>讨论</h2>
        <a href="/community">我的收藏与动态 ↗</a>
      </div>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {!thread ? (
        <p role="status">正在读取互动…</p>
      ) : (
        <>
          <div className="community-actions">
            <button
              className="button-secondary"
              aria-pressed={thread.liked}
              disabled={busy || (!thread.mayParticipate && !thread.liked)}
              onClick={() =>
                void act(() =>
                  api(`${communityRoot}${path}/relations/LIKE`, {
                    method: "PUT",
                    body: { enabled: !thread.liked },
                  }),
                )
              }
            >
              {thread.liked ? "已点赞" : "点赞"} · {thread.likes}
            </button>
            <button
              className="button-secondary"
              aria-pressed={thread.bookmarked}
              disabled={busy || (!thread.mayParticipate && !thread.bookmarked)}
              onClick={() =>
                void act(() =>
                  api(`${communityRoot}${path}/relations/BOOKMARK`, {
                    method: "PUT",
                    body: { enabled: !thread.bookmarked },
                  }),
                )
              }
            >
              {thread.bookmarked ? "已收藏 · 仅自己可见" : "私密收藏"}
            </button>
            <button
              className="button-secondary"
              aria-pressed={thread.following}
              disabled={busy || (!thread.mayParticipate && !thread.following)}
              onClick={() =>
                void act(() =>
                  api(
                    `${communityRoot}/spaces/${encodeURIComponent(space)}/following`,
                    { method: "PUT", body: { enabled: !thread.following } },
                  ),
                )
              }
            >
              {thread.following ? "已关注空间" : "关注这个空间"}
            </button>
          </div>
          {!thread.accountId ? (
            <details>
              <summary>登录后，社区成员及以上可参与互动。</summary>
              <Login onLogin={async () => refresh()} />
            </details>
          ) : (
            !thread.mayParticipate && (
              <p className="muted">
                当前账号可阅读和管理已有记录；社区成员及以上可新增互动。
              </p>
            )
          )}
          {thread.mayParticipate && (
            <CommunityCommentForm
              endpoint={`${communityRoot}${path}/comments`}
              parentId={null}
              onSaved={refresh}
            />
          )}
          <p className="muted community-identity-note">
            评论显示账号昵称；文章署名由作者自行填写。
          </p>
          {thread.comments.items.length === 0 && (
            <p className="muted">还没有评论。</p>
          )}
          {thread.comments.items.map((comment) => (
            <CommunityCommentItem
              key={comment.id}
              comment={comment}
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
                回到最新评论
              </button>
            )}
            {thread.comments.nextBefore !== null && (
              <button
                className="text-button"
                onClick={() => setBefore(thread.comments.nextBefore!)}
              >
                更早的评论
              </button>
            )}
            <button className="text-button" disabled={busy} onClick={refresh}>
              刷新讨论
            </button>
          </div>
        </>
      )}
    </section>
  );
}
