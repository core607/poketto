"use client";
import { useState } from "react";
import type { AccountProfile } from "../lib/types";
import { ReportQueue } from "./community-dashboard";
import { SiteAccounts } from "./site-accounts";

const sections = { accounts: "账号", reports: "举报" };
type Section = keyof typeof sections;

/** Site-wide administration, one section at a time: account groups and reports. */
export function SiteAdministration({ account }: { account: AccountProfile }) {
  const [section, setSection] = useState<Section>("accounts");
  return (
    <div className="management-panel">
      <nav className="segmented" aria-label="站务分区">
        {(Object.keys(sections) as Section[]).map((key) => (
          <button
            key={key}
            type="button"
            aria-current={section === key ? "page" : undefined}
            onClick={() => setSection(key)}
          >
            {sections[key]}
          </button>
        ))}
      </nav>
      {section === "accounts" && <SiteAccounts />}
      {section === "reports" && (
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
