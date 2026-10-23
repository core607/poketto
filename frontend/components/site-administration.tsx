"use client";
import type { AccountProfile } from "../lib/types";
import {
  navigateSite,
  siteChoice,
  siteHref,
  useSiteLocation,
} from "../lib/site-location";
import { ReportQueue } from "./site-reports";
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
  const location = useSiteLocation();
  const section = siteChoice(
    location,
    "section",
    Object.keys(sections) as Section[],
    "overview",
  );
  const open = (next: View) => {
    const fields: Record<string, string | number | null> = {
      section: next.section,
    };
    if (next.section === "accounts")
      Object.assign(fields, {
        accountGroup: next.group ?? null,
        accountOrder: next.order ?? null,
        suspended: next.suspended ? "1" : null,
        accountQuery: null,
        accountOffset: 0,
        account: null,
      });
    if (next.section === "spaces")
      Object.assign(fields, {
        spaceState: next.state ?? null,
        spaceQuery: null,
        spaceOffset: 0,
        workspace: null,
        article: null,
      });
    navigateSite(fields);
  };
  return (
    <div className="management-panel">
      <nav className="segmented" aria-label="站务分区">
        {(Object.keys(sections) as Section[]).map((key) => (
          <a
            key={key}
            href={siteHref({ section: key })}
            aria-current={section === key ? "page" : undefined}
            onClick={(event) => {
              if (
                event.metaKey ||
                event.ctrlKey ||
                event.shiftKey ||
                event.altKey
              )
                return;
              event.preventDefault();
              navigateSite({ section: key });
            }}
          >
            {sections[key]}
          </a>
        ))}
      </nav>
      {section === "overview" && <SiteOverview onOpen={open} />}
      {section === "accounts" && (
        <SiteAccounts currentAccountId={account.account.accountId} />
      )}
      {section === "applications" && <SiteApplications />}
      {section === "spaces" && <SiteSpaces />}
      {section === "reports" && (
        <section className="sub-panel" aria-labelledby="report-queue">
          <div className="panel-heading">
            <h2 id="report-queue">举报处理</h2>
          </div>
          <p className="muted">
            举报不会自动移除评论；移除后评论及其回复不再公开显示。
          </p>
          <ReportQueue />
        </section>
      )}
    </div>
  );
}
