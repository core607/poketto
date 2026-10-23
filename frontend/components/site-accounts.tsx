"use client";
import { FormEvent, useId, useState } from "react";
import { api, message } from "../lib/browser-api";
import { date } from "../lib/format";
import { siteGroups, type SiteGroup } from "../lib/site-groups";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { SiteAccountSpaces } from "./site-review";
import { useModal } from "./use-modal";

type Account = {
  accountId: string;
  loginName: string;
  displayName: string;
  group: SiteGroup;
  ownedSpaces: number;
};
type Change = {
  id: string;
  actorId: string;
  previousGroup: SiteGroup;
  nextGroup: SiteGroup;
  reason: string;
  changedAt: string;
};

export function SiteAccounts() {
  const [query, setQuery] = useState("");
  return (
    <section className="sub-panel" aria-labelledby="site-accounts">
      <div className="panel-heading">
        <h2 id="site-accounts">站点账号</h2>
      </div>
      <form
        className="inline-form"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          setQuery(
            String(new FormData(event.currentTarget).get("query") ?? "").trim(),
          );
        }}
      >
        <label>
          搜索账号、昵称或邮箱
          <input name="query" maxLength={254} type="search" />
        </label>
        <button>搜索</button>
      </form>
      <Accounts key={query} query={query} />
    </section>
  );
}

function Accounts({ query }: { query: string }) {
  const page = useAdminPage<Account>(
    "/api/auth/site/accounts?query=" + encodeURIComponent(query),
  );
  const [selected, setSelected] = useState<{
    account: Account;
    trigger: HTMLElement | null;
  } | null>(null);
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
                  </td>
                  <td data-label="拥有的空间">{account.ownedSpaces}</td>
                  <td data-label="操作">
                    <button
                      type="button"
                      className="button-secondary"
                      onClick={(event) =>
                        setSelected({ account, trigger: event.currentTarget })
                      }
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
        <AccountDialog
          key={selected.account.accountId}
          account={selected.account}
          returnFocus={selected.trigger}
          onChanged={() => {
            page.reload();
            setSelected(null);
          }}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}

/** One account's group, owned spaces and group history, shown over the account list. */
function AccountDialog({
  account,
  returnFocus,
  onChanged,
  onClose,
}: {
  account: Account;
  returnFocus: HTMLElement | null;
  onChanged: () => void;
  onClose: () => void;
}) {
  const dialog = useModal(returnFocus);
  const titleId = useId();
  const [group, setGroup] = useState<SiteGroup>(account.group);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const history = useAdminPage<Change>(
    `/api/auth/site/accounts/${account.accountId}/group-history`,
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
