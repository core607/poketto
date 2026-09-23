"use client";
import type { AccountProfile } from "./account-panel";
import { ReportQueue } from "./community-dashboard";
import { SiteAccounts } from "./site-accounts";

/** Site-wide administration: account groups, published-content review and reports. */
export function SiteAdministration({ account }: { account: AccountProfile }) {
  return (
    <div className="management-panel">
      <SiteAccounts />
      <section className="sub-panel" aria-labelledby="report-queue">
        <div className="panel-heading">
          <h2 id="report-queue">举报处理</h2>
        </div>
        <p className="muted">
          举报不会自动移除评论；移除后评论及其回复不再公开显示。
        </p>
        <ReportQueue account={account} />
      </section>
    </div>
  );
}
