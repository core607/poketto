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
import { LoginDialog } from "./login-dialog";
import { Icon } from "./ui/icons";

export function SpaceFollow({ space }: { space: string }) {
  const [state, setState] = useState<SpaceParticipation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
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
  }, [space, version]);
  if (!state) return null;
  if (!state.accountId)
    return (
      <div className="follow">
        <LoginDialog
          label="关注"
          className="btn btn-secondary"
          onLogin={async () => setVersion((value) => value + 1)}
        />
      </div>
    );
  return (
    <div className="follow">
      <button
        className="btn btn-secondary"
        disabled={busy || (!state.mayParticipate && !state.following)}
        aria-pressed={state.following}
        title={
          !state.mayParticipate && !state.following
            ? "社区成员及以上可关注空间"
            : undefined
        }
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
        <Icon name={state.following ? "check" : "plus"} />
        {state.following ? "已关注" : "关注"}
      </button>
      {!state.mayParticipate && !state.following && (
        <small>社区成员及以上可关注</small>
      )}
      {error && (
        <span role="alert" className="muted">
          {error}
        </span>
      )}
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
  // Without a stable article identity only the space itself can be followed.
  if (!articleId)
    return (
      <section className="community-section" aria-label="关注空间">
        <div className="join-prompt">
          <span>关注这个空间，它的新文章会出现在你的关注动态里。</span>
          <SpaceFollow space={space} />
        </div>
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
  const relation = (kind: "LIKE" | "BOOKMARK", enabled: boolean) =>
    act(() =>
      api(`${communityRoot}${path}/relations/${kind}`, {
        method: "PUT",
        body: { enabled },
      }),
    );
  return (
    <section
      className="community-section"
      aria-label="文章互动"
      id="discussion"
    >
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {!thread ? (
        <p role="status" className="muted">
          正在读取互动…
        </p>
      ) : (
        <>
          <div className="reactions">
            <button
              className="btn btn-secondary"
              aria-pressed={thread.liked}
              disabled={busy || (!thread.mayParticipate && !thread.liked)}
              onClick={() => void relation("LIKE", !thread.liked)}
            >
              <Icon name="heart" />
              {thread.liked ? "已点赞" : "点赞"}
              <span className="count">{thread.likes}</span>
            </button>
            <button
              className="btn btn-secondary"
              aria-pressed={thread.bookmarked}
              disabled={busy || (!thread.mayParticipate && !thread.bookmarked)}
              title="收藏仅自己可见"
              onClick={() => void relation("BOOKMARK", !thread.bookmarked)}
            >
              <Icon name="bookmark" />
              {thread.bookmarked ? "已收藏" : "收藏"}
            </button>
            <button
              className="btn btn-secondary"
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
              <Icon name={thread.following ? "check" : "plus"} />
              {thread.following ? "已关注空间" : "关注空间"}
            </button>
          </div>
          {!thread.accountId ? (
            <div className="join-prompt">
              <span>登录后，社区成员及以上可以点赞、收藏和参与讨论。</span>
              <LoginDialog onLogin={async () => refresh()} />
            </div>
          ) : (
            !thread.mayParticipate && (
              <p className="muted">
                当前账号可阅读和管理已有记录；社区成员及以上可新增互动。
              </p>
            )
          )}
          <div className="community-heading">
            <h2>讨论</h2>
            <a href="/community">我的收藏与动态</a>
          </div>
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
          <div className="comment-list">
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
          </div>
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
