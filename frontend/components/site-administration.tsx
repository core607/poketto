"use client";
import { useState } from "react";
import type { AccountProfile } from "../lib/types";
import { ReportQueue } from "./community-dashboard";
import { SiteAccounts } from "./site-accounts";
import { SiteApplications } from "./site-applications";
import { SiteOverview, type OverviewTarget } from "./site-overview";
import { SiteSpaces } from "./site-spaces";

const sections = {
  overview: "概览",
  accounts: "账号",
  applications: "申请",
  spaces: "空间",
  reports: "举报",
};
type Section = keyof typeof sections;
type View = OverviewTarget | { section: "overview" };

/** Site-wide administration, one section at a time. */
export function SiteAdministration({ account }: { account: AccountProfile }) {
  const [view, setView] = useState<View>({ section: "overview" });
  // Each opening remounts its section so a tile's filter replaces the previous one.
  const [opening, setOpening] = useState(0);
  const open = (next: View) => {
    setView(next);
    setOpening((value) => value + 1);
  };
  return (
    <div className="management-panel">
      <nav className="segmented" aria-label="站务分区">
        {(Object.keys(sections) as Section[]).map((key) => (
          <button
            key={key}
            type="button"
            aria-current={view.section === key ? "page" : undefined}
            onClick={() => open({ section: key } as View)}
          >
            {sections[key]}
          </button>
        ))}
      </nav>
      {view.section === "overview" && <SiteOverview onOpen={open} />}
      {view.section === "accounts" && (
        <SiteAccounts
          key={opening}
          initialGroup={view.group ?? null}
          initialOrder={view.order ?? "NAME"}
          initialSuspended={view.suspended ?? false}
          currentAccountId={account.account.accountId}
        />
      )}
      {view.section === "applications" && <SiteApplications />}
      {view.section === "spaces" && (
        <SiteSpaces key={opening} initialState={view.state ?? "ALL"} />
      )}
      {view.section === "reports" && (
        <section className="sub-panel" aria-labelledby="report-queue">
          <div className="panel-heading">
            <h2 id="report-queue">举报处理</h2>
          </div>
          <p className="muted">
            举报不会自动移除评论；移除后评论及其回复不再公开显示。
          </p>
          <ReportQueue account={account} />
        </section>
      )}
    </div>
  );
}
