"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { articleHref, date, spaceHref } from "../lib/format";
import {
  communityRoot,
  communityMessage,
  type CommunityArticle,
  type CommunityPage,
  type SavedArticle,
  type FollowedSpace,
  type CommunityNotification,
  type BlockedAccount,
  type CommunityReport,
} from "../lib/community";
import { Login } from "./login";
import type { AccountProfile } from "./account-panel";

type Tab =
  | "feed"
  | "bookmarks"
  | "likes"
  | "notifications"
  | "following"
  | "blocks"
  | "reports";
type Results = {
  feed?: { items: CommunityArticle[]; nextCursor: string | null };
  bookmarks?: CommunityPage<SavedArticle>;
  likes?: CommunityPage<SavedArticle>;
  notifications?: CommunityPage<CommunityNotification>;
  following?: FollowedSpace[];
  blocks?: CommunityPage<BlockedAccount>;
  reports?: CommunityPage<CommunityReport>;
};
const labels: Record<Tab, string> = {
  feed: "关注动态",
  bookmarks: "私密收藏",
  likes: "点赞记录",
  notifications: "通知",
  following: "关注的空间",
  blocks: "屏蔽名单",
  reports: "举报处理",
};

export function CommunityDashboard({
  initialTab = "feed",
  embedded = false,
}: {
  initialTab?: "feed" | "bookmarks";
  embedded?: boolean;
}) {
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [checking, setChecking] = useState(true);
  const [error, setError] = useState("");
  async function login() {
    setChecking(true);
    setError("");
    try {
      setAccount(await api<AccountProfile>("/api/auth/account"));
    } catch (failure) {
      setAccount(null);
      if (!(failure instanceof ApiError && failure.status === 401))
        setError(communityMessage(failure));
    } finally {
      setChecking(false);
    }
  }
  useEffect(() => {
    void login();
  }, []);
  if (checking) return <p role="status">正在确认会话…</p>;
  return (
    <div
      className={
        embedded ? "community-dashboard" : "page-shell community-dashboard"
      }
    >
      {!embedded && (
        <header className="page-heading">
          <p className="eyebrow">我的社区</p>
          <h1>{account ? account.account.displayName : "收藏、关注与讨论"}</h1>
          <p className="muted">
            收藏与通知仅自己可见；关注动态按文章时间排列。
          </p>
        </header>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {account ? (
        <CommunityLists
          account={account}
          initialTab={initialTab}
          feedOnly={embedded}
        />
      ) : (
        <Login onLogin={login} />
      )}
    </div>
  );
}

function CommunityLists({
  account,
  initialTab,
  feedOnly,
}: {
  account: AccountProfile;
  initialTab: "feed" | "bookmarks";
  feedOnly: boolean;
}) {
  const [tab, setTab] = useState<Tab>(initialTab);
  const [before, setBefore] = useState(0);
  const [cursor, setCursor] = useState<string | null>(null);
  const [results, setResults] = useState<Results>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const [removeReport, setRemoveReport] = useState<number | null>(null);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    setResults({});
    const query =
      tab === "feed"
        ? cursor
          ? `?cursor=${encodeURIComponent(cursor)}`
          : ""
        : `?before=${before}`;
    void api<Results[typeof tab]>(`${communityRoot}/${tab}${query}`)
      .then((value) => {
        if (active) setResults({ [tab]: value });
      })
      .catch((failure) => {
        if (active) setError(communityMessage(failure));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [tab, before, cursor, version]);
  async function act(operation: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await operation();
      setVersion((value) => value + 1);
    } catch (failure) {
      setError(communityMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  const tabs = (Object.keys(labels) as Tab[]).filter(
    (item) => item !== "reports" || account.account.siteAdministrator,
  );
  const page =
    results.bookmarks ??
    results.likes ??
    results.notifications ??
    results.blocks ??
    results.reports;
  const count =
    results.feed?.items.length ??
    results.following?.length ??
    page?.items.length;
  return (
    <>
      {!feedOnly && (
        <nav className="community-tabs" aria-label="我的社区栏目">
          {tabs.map((item) => (
            <button
              key={item}
              className={item === tab ? "" : "button-secondary"}
              aria-current={item === tab ? "page" : undefined}
              onClick={() => {
                setTab(item);
                setBefore(0);
                setCursor(null);
                setRemoveReport(null);
              }}
            >
              {labels[item]}
            </button>
          ))}
        </nav>
      )}
      {feedOnly && (
        <p className="muted">
          来自你关注的空间，按文章时间排列。
          <a href="/community">管理关注与通知 ↗</a>
        </p>
      )}
      {account.account.group === "VIEWER" && (
        <p className="notice">
          当前账号可管理已有记录。社区成员及以上可新增收藏、关注和评论。
        </p>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {loading && <p role="status">正在读取…</p>}
      {!loading && count === 0 && (
        <p className="muted">
          这一页没有可显示的记录。
          {page?.nextBefore !== null && page?.nextBefore !== undefined
            ? "可以继续查看更早记录。"
            : ""}
        </p>
      )}
      {results.feed?.items.map((article) => (
        <ArticleCard
          key={`${article.space}/${article.route}`}
          article={article}
        />
      ))}
      {(results.bookmarks ?? results.likes)?.items.map((item) => (
        <div className="community-list-item" key={item.position}>
          {item.article ? (
            <ArticleCard article={item.article} />
          ) : (
            <p className="muted">
              {results.likes ? "点赞" : "收藏"}的文章目前不可用。
            </p>
          )}
          <button
            className="text-button"
            disabled={busy}
            onClick={() =>
              void act(() =>
                api(
                  `${communityRoot}/${results.likes ? "likes" : "bookmarks"}/${item.position}`,
                  {
                    method: "DELETE",
                  },
                ),
              )
            }
          >
            {results.likes ? "取消点赞" : "取消收藏"}
          </button>
        </div>
      ))}
      {results.following?.map((item) => (
        <div className="community-list-item" key={item.position}>
          {item.available && item.space ? (
            <a href={spaceHref(item.space)}>
              <strong>{item.displayName}</strong>
            </a>
          ) : (
            <p className="muted">关注的空间目前不可用。</p>
          )}
          <button
            className="text-button"
            disabled={busy}
            onClick={() =>
              void act(() =>
                api(`${communityRoot}/following/${item.position}`, {
                  method: "DELETE",
                }),
              )
            }
          >
            取消关注
          </button>
        </div>
      ))}
      {results.notifications?.items.map((item) => (
        <div className="community-list-item" key={item.position}>
          <div className="community-comment-meta">
            <strong>{item.actor?.displayName || "账号用户"}</strong>
            <span>{item.read ? "已读" : "未读"}</span>
            <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
          </div>
          <p className="community-comment-body">{item.excerpt}</p>
          <a
            href={
              articleHref(item.article.route, item.article.space) +
              "#discussion"
            }
          >
            查看《{item.article.title}》的讨论 ↗
          </a>
          {!item.read && (
            <button
              className="text-button"
              disabled={busy}
              onClick={() =>
                void act(() =>
                  api(`${communityRoot}/notifications/read`, {
                    method: "POST",
                    body: { positions: [item.position] },
                  }),
                )
              }
            >
              标为已读
            </button>
          )}
        </div>
      ))}
      {results.blocks?.items.map((item) => (
        <div className="community-list-item" key={item.position}>
          <strong>{item.account?.displayName || "不可用的账号"}</strong>
          {item.account && (
            <button
              className="text-button"
              disabled={busy}
              onClick={() =>
                void act(() =>
                  api(`${communityRoot}/blocks/${item.account!.accountId}`, {
                    method: "PUT",
                    body: { enabled: false },
                  }),
                )
              }
            >
              取消屏蔽
            </button>
          )}
        </div>
      ))}
      {results.reports?.items.map((item) => (
        <div className="community-list-item" key={item.position}>
          <div className="community-comment-meta">
            <strong>举报人：{item.reporter?.displayName || "账号用户"}</strong>
            <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
          </div>
          <p>原因：{item.reason}</p>
          <blockquote className="community-comment-body">
            {item.author?.displayName || "账号用户"}：
            {item.commentBody || "评论已删除"}
          </blockquote>
          <div className="community-actions">
            <button
              disabled={busy}
              onClick={() => setRemoveReport(item.position)}
            >
              移除评论
            </button>
            <button
              className="button-secondary"
              disabled={busy}
              onClick={() =>
                void act(() =>
                  api(`${communityRoot}/reports/${item.position}/resolve`, {
                    method: "POST",
                    body: { remove: false },
                  }),
                )
              }
            >
              无需处理
            </button>
          </div>
          {removeReport === item.position && (
            <div className="notice">
              <p>确认移除这条评论及其回复？操作后将不再公开显示。</p>
              <button
                disabled={busy}
                onClick={() =>
                  void act(() =>
                    api(`${communityRoot}/reports/${item.position}/resolve`, {
                      method: "POST",
                      body: { remove: true },
                    }),
                  )
                }
              >
                确认移除
              </button>
              <button
                className="text-button"
                onClick={() => setRemoveReport(null)}
              >
                取消
              </button>
            </div>
          )}
        </div>
      ))}
      <div className="community-actions">
        {(before > 0 || cursor) && (
          <button
            className="text-button"
            onClick={() => {
              setBefore(0);
              setCursor(null);
            }}
          >
            返回最新
          </button>
        )}
        {page?.nextBefore != null && (
          <button
            className="text-button"
            disabled={loading}
            onClick={() => setBefore(page.nextBefore!)}
          >
            更早记录
          </button>
        )}
        {results.feed?.nextCursor && (
          <button
            className="text-button"
            disabled={loading}
            onClick={() => setCursor(results.feed!.nextCursor)}
          >
            更早动态
          </button>
        )}
        <button
          className="text-button"
          disabled={loading || busy}
          onClick={() => setVersion((value) => value + 1)}
        >
          刷新
        </button>
        <a href="/admin">管理账号 ↗</a>
      </div>
    </>
  );
}

function ArticleCard({ article }: { article: CommunityArticle }) {
  return (
    <article className="community-article-card">
      <p className="article-meta">
        <a href={spaceHref(article.space)}>{article.spaceName}</a>
        <time dateTime={article.createdAt}>{date(article.createdAt)}</time>
      </p>
      <h2>
        <a href={articleHref(article.route, article.space)}>{article.title}</a>
      </h2>
      <p className="muted">作者署名：{article.authorName}</p>
    </article>
  );
}
