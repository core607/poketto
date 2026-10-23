"use client";
import { useEffect, useState } from "react";
import { api, message } from "../lib/browser-api";
import { siteGroups, type SiteGroup } from "../lib/site-groups";

type Overview = {
  accounts: Record<SiteGroup, number>;
  newAccounts: number;
  suspendedAccounts: number;
  spaces: number;
  publicSpaces: number;
  restrictedSpaces: number;
  openReports: number;
  openApplications: number;
};
export type OverviewTarget =
  | {
      section: "accounts";
      group?: SiteGroup;
      order?: "NEWEST";
      suspended?: boolean;
    }
  | { section: "spaces"; state?: "PUBLIC" | "RESTRICTED" }
  | { section: "reports" }
  | { section: "applications" };

/** Site-wide counts; each tile opens the list it counts. */
export function SiteOverview({
  onOpen,
}: {
  onOpen: (target: OverviewTarget) => void;
}) {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<Overview>("/api/auth/site/overview")
      .then((value) => {
        if (active) setOverview(value);
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    return () => {
      active = false;
    };
  }, []);
  if (error)
    return (
      <p role="alert" className="notice danger">
        {error}
      </p>
    );
  if (!overview)
    return (
      <p role="status" className="muted">
        正在读取站点概况…
      </p>
    );
  const total = Object.values(overview.accounts).reduce((a, b) => a + b, 0);
  const tile = (
    label: string,
    value: number,
    target: OverviewTarget,
    note?: string,
  ) => (
    <button
      type="button"
      className="stat-tile"
      key={label}
      onClick={() => onOpen(target)}
    >
      <span className="stat-label">{label}</span>
      <strong className="stat-value">{value.toLocaleString("zh-CN")}</strong>
      {note && <span className="stat-note">{note}</span>}
    </button>
  );
  return (
    <div className="management-panel">
      <section className="sub-panel" aria-labelledby="overview-attention">
        <div className="panel-heading">
          <h2 id="overview-attention">待处理</h2>
        </div>
        <div className="stat-row">
          {tile("待审核的创作者申请", overview.openApplications, {
            section: "applications",
          })}
          {tile("未处理举报", overview.openReports, { section: "reports" })}
          {tile(
            "展示受限的空间",
            overview.restrictedSpaces,
            { section: "spaces", state: "RESTRICTED" },
            "所有者缺少创作者资格、已被停用，或空间没有所有者",
          )}
          {tile(
            "近 7 天新注册",
            overview.newAccounts,
            { section: "accounts", order: "NEWEST" },
            "点开后按注册时间从新到旧列出全部账号",
          )}
        </div>
      </section>
      <section className="sub-panel" aria-labelledby="overview-accounts">
        <div className="panel-heading">
          <h2 id="overview-accounts">账号</h2>
        </div>
        <div className="stat-row">
          {tile("全部账号", total, { section: "accounts" })}
          {(Object.keys(siteGroups) as SiteGroup[]).map((group) =>
            tile(siteGroups[group], overview.accounts[group] ?? 0, {
              section: "accounts",
              group,
            }),
          )}
          {tile("已停用", overview.suspendedAccounts, {
            section: "accounts",
            suspended: true,
          })}
        </div>
      </section>
      <section className="sub-panel" aria-labelledby="overview-spaces">
        <div className="panel-heading">
          <h2 id="overview-spaces">空间</h2>
        </div>
        <div className="stat-row">
          {tile("全部空间", overview.spaces, { section: "spaces" })}
          {tile("网站公开中", overview.publicSpaces, {
            section: "spaces",
            state: "PUBLIC",
          })}
        </div>
      </section>
    </div>
  );
}
