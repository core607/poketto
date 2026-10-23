"use client";
import { FormEvent, useEffect, useId, useRef, useState } from "react";
import { api, message } from "../lib/browser-api";
import { date } from "../lib/format";
import { siteGroups, type SiteGroup } from "../lib/site-groups";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { SiteAccountSpaces } from "./site-review";
import { useModal } from "./use-modal";
import {
  navigateSite,
  pageOffset,
  siteChoice,
  useSiteLocation,
} from "../lib/site-location";

type Account = {
  accountId: string;
  loginName: string;
  displayName: string;
  group: SiteGroup;
  ownedSpaces: number;
  createdAt: string;
  suspended: boolean;
};
type Order = "NAME" | "NEWEST";
type Change = {
  id: string;
  actorId: string;
  previousGroup: SiteGroup;
  nextGroup: SiteGroup;
  reason: string;
  changedAt: string;
};
type SuspensionChange = {
  id: string;
  suspended: boolean;
  reason: string;
  changedAt: string;
};

export function SiteAccounts({
  currentAccountId = "",
}: {
  currentAccountId?: string;
}) {
  const location = useSiteLocation();
  const query = (location.get("accountQuery") ?? "").slice(0, 254);
  const group =
    siteChoice(
      location,
      "accountGroup",
      ["", ...Object.keys(siteGroups)] as ("" | SiteGroup)[],
      "",
    ) || null;
  const suspended = location.get("suspended") === "1";
  const order = siteChoice<Order>(
    location,
    "accountOrder",
    ["NAME", "NEWEST"],
    "NAME",
  );
  const filter = (values: Record<string, string | null>) =>
    navigateSite({
      ...values,
      accountOffset: 0,
      account: null,
      workspace: null,
      article: null,
    });
  return (
    <section className="sub-panel" aria-labelledby="site-accounts">
      <div className="panel-heading">
        <h2 id="site-accounts">站点账号</h2>
      </div>
      <form
        key={query}
        className="inline-form"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          filter({
            accountQuery: String(
              new FormData(event.currentTarget).get("query") ?? "",
            ).trim(),
          });
        }}
      >
        <label>
          搜索账号、昵称或邮箱
          <input
            name="query"
            maxLength={254}
            type="search"
            defaultValue={query}
          />
        </label>
        <button>搜索</button>
      </form>
      <div className="filter-bar">
        <div className="filter-chips" role="group" aria-label="按策略组筛选">
          {([null, ...Object.keys(siteGroups)] as (SiteGroup | null)[]).map(
            (key) => (
              <button
                key={key ?? "all"}
                type="button"
                aria-pressed={group === key}
                onClick={() => filter({ accountGroup: key })}
              >
                {key ? siteGroups[key] : "全部"}
              </button>
            ),
          )}
          <button
            type="button"
            aria-pressed={suspended}
            onClick={() => filter({ suspended: suspended ? null : "1" })}
          >
            只看已停用
          </button>
        </div>
        <label className="inline-select">
          排序
          <select
            value={order}
            onChange={(event) => filter({ accountOrder: event.target.value })}
          >
            <option value="NAME">按账号名</option>
            <option value="NEWEST">最新注册</option>
          </select>
        </label>
      </div>
      <Accounts
        key={[query, group, suspended, order].join("\n")}
        query={query}
        group={group}
        suspended={suspended}
        order={order}
        currentAccountId={currentAccountId}
      />
    </section>
  );
}

function Accounts({
  query,
  group,
  suspended,
  order,
  currentAccountId,
}: {
  query: string;
  group: SiteGroup | null;
  suspended: boolean;
  order: Order;
  currentAccountId: string;
}) {
  const location = useSiteLocation();
  const trigger = useRef<HTMLElement | null>(null);
  const selected = location.get("account");
  const page = useAdminPage<Account>(
    "/api/auth/site/accounts?query=" +
      encodeURIComponent(query) +
      (group ? "&group=" + group : "") +
      (suspended ? "&suspended=true" : "") +
      (order === "NAME" ? "" : "&order=" + order),
    {
      offset: pageOffset(location, "accountOffset"),
      setOffset: (offset) =>
        navigateSite({ accountOffset: offset, account: null }),
    },
  );
  return (
    <>
      {page.error && (
        <p role="alert" className="notice danger">
          {page.error}
        </p>
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>账号</th>
              <th>策略组</th>
              <th>拥有的空间</th>
              <th>注册时间</th>
              <th>
                <span className="sr-only">操作</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {!page.loading &&
              !page.error &&
              page.items.map((account) => (
                <tr key={account.accountId}>
                  <td data-label="账号">
                    <strong>{account.displayName}</strong>
                    <small className="muted"> · {account.loginName}</small>
                  </td>
                  <td data-label="策略组">
                    <span className="group-badge">
                      {siteGroups[account.group]}
                    </span>
                    {account.suspended && (
                      <span className="state-chip warn">已停用</span>
                    )}
                  </td>
                  <td data-label="拥有的空间">{account.ownedSpaces}</td>
                  <td data-label="注册时间">{date(account.createdAt)}</td>
                  <td data-label="操作">
                    <button
                      type="button"
                      className="button-secondary"
                      onClick={(event) => {
                        trigger.current = event.currentTarget;
                        navigateSite({
                          account: account.accountId,
                          historyOffset: 0,
                          ownedOffset: 0,
                          workspace: null,
                          article: null,
                        });
                      }}
                    >
                      管理
                      <span className="sr-only"> {account.displayName}</span>
                    </button>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      {!page.loading && !page.error && page.items.length === 0 && (
        <p className="muted">没有找到匹配的账号。</p>
      )}
      <AdminPagination label="站点账号" page={page} />
      {selected && (
        <SelectedAccount
          key={selected}
          accountId={selected}
          currentAccountId={currentAccountId}
          returnFocus={trigger.current}
          onChanged={() => {
            page.reload();
            navigateSite({ account: null, workspace: null, article: null });
          }}
          onClose={() =>
            navigateSite({ account: null, workspace: null, article: null })
          }
        />
      )}
    </>
  );
}

function SelectedAccount({
  accountId,
  currentAccountId,
  returnFocus,
  onChanged,
  onClose,
}: {
  accountId: string;
  currentAccountId: string;
  returnFocus: HTMLElement | null;
  onChanged: () => void;
  onClose: () => void;
}) {
  const [account, setAccount] = useState<Account | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<Account>("/api/auth/site/accounts/" + encodeURIComponent(accountId))
      .then((value) => {
        if (active) setAccount(value);
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    return () => {
      active = false;
    };
  }, [accountId]);
  if (!account)
    return (
      <p role={error ? "alert" : "status"}>
        {error || "正在读取账号…"} <button onClick={onClose}>关闭</button>
      </p>
    );
  return (
    <AccountDialog
      account={account}
      protectedAccount={
        account.group === "ADMINISTRATOR" ||
        account.accountId === currentAccountId
      }
      returnFocus={returnFocus}
      onChanged={onChanged}
      onClose={onClose}
    />
  );
}

/** One account's group, owned spaces and group history, shown over the account list. */
function AccountDialog({
  account,
  protectedAccount,
  returnFocus,
  onChanged,
  onClose,
}: {
  account: Account;
  protectedAccount: boolean;
  returnFocus: HTMLElement | null;
  onChanged: () => void;
  onClose: () => void;
}) {
  const dialog = useModal(returnFocus);
  const titleId = useId();
  const [group, setGroup] = useState<SiteGroup>(account.group);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const location = useSiteLocation();
  const history = useAdminPage<Change>(
    `/api/auth/site/accounts/${account.accountId}/group-history`,
    {
      offset: pageOffset(location, "historyOffset"),
      setOffset: (offset) => navigateSite({ historyOffset: offset }),
    },
  );
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const reason = String(
      new FormData(event.currentTarget).get("reason") ?? "",
    );
    setPending(true);
    setError("");
    try {
      await api(`/api/auth/site/accounts/${account.accountId}/group`, {
        method: "PUT",
        body: { group, reason },
      });
      onChanged();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="side-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onClose();
      }}
    >
      <header className="side-dialog-head">
        <div>
          <h2 id={titleId}>{account.displayName}</h2>
          <p className="muted">
            {account.loginName} · {siteGroups[account.group]}
          </p>
        </div>
        <button
          type="button"
          className="text-button"
          onClick={onClose}
          disabled={pending}
        >
          关闭
        </button>
      </header>
      <section
        aria-label={`${account.displayName} 的策略组`}
        className="admin-block"
      >
        <h3>策略组</h3>
        <form onSubmit={save} className="stack-form">
          <label>
            新的策略组
            <select
              value={group}
              onChange={(event) => setGroup(event.target.value as SiteGroup)}
              disabled={pending}
            >
              {Object.entries(siteGroups).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          {group === "VIEWER" || group === "COMMUNITY" ? (
            <p className="form-help">
              该账号拥有的 {account.ownedSpaces}{" "}
              个空间将停止公开展示。已有空间的编辑和成员权限保留。
            </p>
          ) : (
            <p className="form-help">
              具备创建和公开展示空间的资格；原先开启的网站会恢复展示。站点管理员不会自动获得他人私有空间的访问权限。
            </p>
          )}
          <label>
            变更原因
            <textarea
              name="reason"
              required
              maxLength={500}
              rows={3}
              disabled={pending}
            />
          </label>
          <div className="button-row">
            <button disabled={pending || group === account.group}>
              {pending ? "正在保存…" : "保存策略组"}
            </button>
          </div>
        </form>
        {error && (
          <p role="alert" className="notice danger">
            {error}
          </p>
        )}
      </section>
      <Suspension
        account={account}
        protectedAccount={protectedAccount}
        onChanged={onChanged}
      />
      <SiteAccountSpaces accountId={account.accountId} />
      <section aria-label="策略组变更记录" className="admin-block">
        <h3>变更记录</h3>
        {history.error && (
          <p role="alert" className="notice danger">
            {history.error}
          </p>
        )}
        {!history.loading && !history.error && history.items.length === 0 && (
          <p className="muted">还没有变更记录。</p>
        )}
        <ul className="admin-list history">
          {!history.loading &&
            !history.error &&
            history.items.map((change) => (
              <li key={change.id}>
                <span className="admin-list-main">
                  <strong>
                    {siteGroups[change.previousGroup]} →{" "}
                    {siteGroups[change.nextGroup]}
                  </strong>
                  <small className="muted">{date(change.changedAt)}</small>
                </span>
                <p>{change.reason}</p>
              </li>
            ))}
        </ul>
        <AdminPagination
          label="策略组变更记录"
          page={history}
          disabled={pending}
        />
      </section>
    </dialog>
  );
}

/** Suspending ends the account's sessions and keys and withdraws websites it owns; restoring undoes the block. */
function Suspension({
  account,
  protectedAccount,
  onChanged,
}: {
  account: Account;
  protectedAccount: boolean;
  onChanged: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const history = useAdminPage<SuspensionChange>(
    `/api/auth/site/accounts/${account.accountId}/suspension-history`,
  );
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const reason = String(
      new FormData(event.currentTarget).get("reason") ?? "",
    );
    setPending(true);
    setError("");
    try {
      await api(`/api/auth/site/accounts/${account.accountId}/suspension`, {
        method: "PUT",
        body: { suspended: !account.suspended, reason },
      });
      onChanged();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  const latest = history.items[0];
  return (
    <section
      aria-label={`${account.displayName} 的账号状态`}
      className="admin-block"
    >
      <h3>账号状态</h3>
      {account.suspended ? (
        <p>
          <span className="state-chip warn">已停用</span>{" "}
          {latest?.suspended && `${date(latest.changedAt)}：${latest.reason}`}
        </p>
      ) : (
        <p className="muted">正常。</p>
      )}
      {protectedAccount ? (
        <p className="form-help">
          不能停用自己或站点管理员；如需停用，请先调整对方的策略组。
        </p>
      ) : (
        <form onSubmit={save} className="stack-form">
          <p className="form-help">
            {account.suspended
              ? "恢复后对方需要重新登录；之前的访问密钥和 AI 连接已失效，需要重新创建。"
              : "停用会立即退出对方的所有登录、撤销访问密钥和 AI 连接，并暂停其拥有的网站。空间、成员关系和内容都会保留。"}
          </p>
          <label>
            {account.suspended ? "恢复原因" : "停用原因"}
            <textarea
              name="reason"
              required
              maxLength={500}
              rows={2}
              disabled={pending}
            />
          </label>
          <div className="button-row">
            <button
              className={account.suspended ? undefined : "button-danger"}
              disabled={pending}
            >
              {account.suspended ? "恢复账号" : "停用账号"}
            </button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </section>
  );
}
