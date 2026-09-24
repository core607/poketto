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
  noticeAction,
  type CommunityNotification,
  type BlockedAccount,
  type CommunityReport,
} from "../lib/community";
import { Login } from "./login";
import type { CommunityTab } from "../lib/community";
import type { AccountProfile } from "./account-panel";
import { Avatar, Icon, type IconName } from "./ui/icons";

type Tab = CommunityTab;
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
const icons: Record<Tab, IconName> = {
  feed: "compass",
  bookmarks: "bookmark",
  likes: "heart",
  notifications: "bell",
  following: "users",
  blocks: "block",
  reports: "flag",
};
/** Personal lists; the report queue belongs to site administration. */
const personal: Tab[] = [
  "notifications",
  "bookmarks",
  "likes",
  "following",
  "feed",
  "blocks",
];

export function CommunityDashboard({
  initialTab = "feed",
  embedded = false,
}: {
  initialTab?: Tab;
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
  if (checking)
    return (
      <p role="status" className="muted">
        正在确认会话…
      </p>
    );
  const body = (
    <>
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
      ) : embedded ? (
        <div className="join-prompt">
          <span>登录后，这里会按时间列出你关注的空间里的新文章。</span>
          <a className="btn btn-primary btn-sm" href="/admin">
            登录
          </a>
        </div>
      ) : (
        <Login onLogin={login} />
      )}
    </>
  );
  if (embedded) return <div className="community-dashboard">{body}</div>;
  return (
    <div className="page page-narrow community-dashboard">
      {account && (
        <header className="page-head personal-head">
          <Avatar name={account.account.displayName} large />
          <div>
            <h1>{account.account.displayName}</h1>
            <p>收藏、点赞和通知只有你自己能看到。</p>
          </div>
        </header>
      )}
      {body}
    </div>
  );
}

/** The site-wide report queue, shown to site administrators. */
export function ReportQueue({ account }: { account: AccountProfile }) {
  return <CommunityLists account={account} initialTab="reports" fixed />;
}

function CommunityLists({
  account,
  initialTab,
  feedOnly = false,
  fixed = false,
}: {
  account: AccountProfile;
  initialTab: Tab;
  feedOnly?: boolean;
  fixed?: boolean;
}) {
  const [tab, setTab] = useState<Tab>(
    initialTab === "reports" && !fixed ? "notifications" : initialTab,
  );
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
      {!feedOnly && !fixed && (
        <nav className="personal-tabs" aria-label="我的社区栏目">
          {personal.map((item) => (
            <button
              key={item}
              type="button"
              aria-current={item === tab ? "page" : undefined}
              onClick={() => {
                setTab(item);
                setBefore(0);
                setCursor(null);
                setRemoveReport(null);
              }}
            >
              <Icon name={icons[item]} />
              {labels[item]}
            </button>
          ))}
        </nav>
      )}
      {account.account.group === "VIEWER" && !fixed && (
        <p className="notice">
          当前账号可管理已有记录。社区成员及以上可新增收藏、关注和评论。
        </p>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      {loading && (
        <p role="status" className="muted">
          正在读取…
        </p>
      )}
      {!loading && count === 0 && (
        <div className="empty">
          <span className="empty-mark">
            <Icon name={icons[tab]} />
          </span>
          <h3>这一页没有可显示的记录。</h3>
          <p>
            {page?.nextBefore !== null && page?.nextBefore !== undefined
              ? "可以继续查看更早记录。"
              : tab === "feed"
                ? "关注一些空间，它们的新文章会出现在这里。"
                : "有新内容时会出现在这里。"}
          </p>
        </div>
      )}
      <div className="personal-list">
        {results.feed?.items.map((article) => (
          <ArticleCard
            key={`${article.space}/${article.route}`}
            article={article}
          />
        ))}
        {(results.bookmarks ?? results.likes)?.items.map((item) => (
          <div className="personal-item" key={item.position}>
            {item.article ? (
              <ArticleCard article={item.article} />
            ) : (
              <p className="muted">
                {results.likes ? "点赞" : "收藏"}的文章目前不可用。
              </p>
            )}
            <button
              className="btn btn-ghost btn-sm"
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
          <div className="personal-item" key={item.position}>
            {item.available && item.space ? (
              <a className="space-link" href={spaceHref(item.space)}>
                <Avatar name={item.displayName ?? item.space} />
                {item.displayName}
              </a>
            ) : (
              <p className="muted">关注的空间目前不可用。</p>
            )}
            <button
              className="btn btn-ghost btn-sm"
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
          <div
            className={
              item.read ? "personal-item" : "personal-item personal-unread"
            }
            key={item.position}
          >
            <div className="personal-body">
              <div className="community-comment-meta">
                <strong>{item.actor?.displayName || "账号用户"}</strong>
                <span>{noticeAction(item)}</span>
                <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
              </div>
              {item.excerpt && (
                <p className="community-comment-body">{item.excerpt}</p>
              )}
              <a
                className="link-btn"
                href={
                  articleHref(item.article.route, item.article.space) +
                  (item.event ? "" : "#discussion")
                }
              >
                {item.event ? "查看文章" : "查看讨论"}
              </a>
            </div>
            {!item.read && (
              <button
                className="btn btn-ghost btn-sm"
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
          <div className="personal-item" key={item.position}>
            <strong>{item.account?.displayName || "不可用的账号"}</strong>
            {item.account && (
              <button
                className="btn btn-ghost btn-sm"
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
          <div className="report-item" key={item.position}>
            <div className="community-comment-meta">
              <strong>
                举报人：{item.reporter?.displayName || "账号用户"}
              </strong>
              <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
            </div>
            <p>原因：{item.reason}</p>
            <blockquote className="community-comment-body">
              {item.author?.displayName || "账号用户"}：
              {item.commentBody || "评论已删除"}
            </blockquote>
            <div className="community-actions">
              <button
                className="btn btn-danger btn-sm"
                disabled={busy}
                onClick={() => setRemoveReport(item.position)}
              >
                移除评论
              </button>
              <button
                className="btn btn-secondary btn-sm"
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
              <div className="notice danger">
                <p>确认移除这条评论及其回复？操作后将不再公开显示。</p>
                <div className="community-actions">
                  <button
                    className="btn btn-danger btn-sm"
                    disabled={busy}
                    onClick={() =>
                      void act(() =>
                        api(
                          `${communityRoot}/reports/${item.position}/resolve`,
                          {
                            method: "POST",
                            body: { remove: true },
                          },
                        ),
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
              </div>
            )}
          </div>
        ))}
      </div>
      <div className="community-actions personal-more">
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
      </div>
    </>
  );
}

function ArticleCard({ article }: { article: CommunityArticle }) {
  return (
    <article className="entry">
      <div className="card-meta">
        <a className="space-link" href={spaceHref(article.space)}>
          <Avatar name={article.spaceName} />
          {article.spaceName}
        </a>
        {article.authorName !== article.spaceName && (
          <span className="author-name">{article.authorName}</span>
        )}
        <time dateTime={article.createdAt}>{date(article.createdAt)}</time>
      </div>
      <h2>
        <a href={articleHref(article.route, article.space)}>{article.title}</a>
      </h2>
    </article>
  );
}
