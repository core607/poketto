"use client";
import { FormEvent, useState } from "react";
import { api } from "../lib/browser-api";
import { date } from "../lib/format";
import { message } from "./admin";
import { AdminPagination } from "./admin-pagination";

import { useSitePage } from "./site-page";
import { SiteAccountSpaces } from "./site-review";

export const siteGroups = {
  VIEWER: "浏览者",
  COMMUNITY: "社区成员",
  CREATOR: "创作者",
  ADMINISTRATOR: "站点管理员",
};
export type SiteGroup = keyof typeof siteGroups;
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
    <section className="sub-panel">
      <h2>站点账号管理</h2>
      <form
        className="inline-form"
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
  const page = useSitePage<Account>(
    "/api/auth/site/accounts?query=" + encodeURIComponent(query),
  );
  const [selected, setSelected] = useState<Account | null>(null);
  return (
    <>
      {page.error && <p role="alert">{page.error}</p>}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>账号</th>
              <th>策略组</th>
              <th>拥有的空间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {!page.loading &&
              !page.error &&
              page.items.map((account) => (
                <tr key={account.accountId}>
                  <td data-label="账号">
                    {account.displayName}
                    <small className="muted"> · {account.loginName}</small>
                  </td>
                  <td data-label="策略组">{siteGroups[account.group]}</td>
                  <td data-label="拥有的空间">{account.ownedSpaces}</td>
                  <td data-label="操作">
                    <button onClick={() => setSelected(account)}>
                      管理 {account.displayName}
                    </button>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      <AdminPagination label="站点账号" page={page} />
      {selected && (
        <AccountGroup
          key={selected.accountId}
          account={selected}
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

function AccountGroup({
  account,
  onChanged,
  onClose,
}: {
  account: Account;
  onChanged: () => void;
  onClose: () => void;
}) {
  const [group, setGroup] = useState<SiteGroup>(account.group);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const history = useSitePage<Change>(
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
    <section
      aria-label={`${account.displayName} 的策略组`}
      className="sub-panel"
    >
      <h3>{account.displayName}</h3>
      <form onSubmit={save}>
        <label>
          策略组
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
          <p>
            该账号拥有的 {account.ownedSpaces}{" "}
            个空间将停止公开展示。已有空间的编辑和成员权限保留。
          </p>
        ) : (
          <p>
            具备创建和公开展示空间的资格；原先开启的网站会恢复展示。站点管理员不会自动获得他人私有空间的访问权限。
          </p>
        )}
        <label>
          变更原因
          <textarea name="reason" required maxLength={500} disabled={pending} />
        </label>
        <button disabled={pending || group === account.group}>
          {pending ? "正在保存…" : "保存策略组"}
        </button>
        <button type="button" onClick={onClose} disabled={pending}>
          关闭
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
      <SiteAccountSpaces accountId={account.accountId} />
      <h4>变更记录</h4>
      {history.error && <p role="alert">{history.error}</p>}
      {!history.loading &&
        !history.error &&
        history.items.map((change) => (
          <article key={change.id}>
            <p>
              {siteGroups[change.previousGroup]} →{" "}
              {siteGroups[change.nextGroup]} · {date(change.changedAt)}
            </p>
            <p>{change.reason}</p>
          </article>
        ))}
      <AdminPagination
        label="策略组变更记录"
        page={history}
        disabled={pending}
      />
    </section>
  );
}
