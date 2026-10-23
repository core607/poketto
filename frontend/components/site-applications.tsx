"use client";
import { useRef, useState } from "react";
import { api, message } from "../lib/browser-api";
import { date } from "../lib/format";
import { siteGroups, type SiteGroup } from "../lib/site-groups";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import {
  navigateSite,
  pageOffset,
  siteChoice,
  useSiteLocation,
} from "../lib/site-location";

type Status = "OPEN" | "APPROVED" | "DECLINED";
const statuses: Record<Status, string> = {
  OPEN: "待审核",
  APPROVED: "已批准",
  DECLINED: "未通过",
};
type Queued = {
  application: {
    id: string;
    statement: string;
    status: Status;
    createdAt: string;
    decidedAt: string | null;
    decisionNote: string | null;
  };
  accountId: string;
  loginName: string;
  displayName: string;
  group: SiteGroup;
};

/** Creator applications; approving grants the creator group through the audited group change. */
export function SiteApplications() {
  const location = useSiteLocation();
  const status = siteChoice(
    location,
    "applicationStatus",
    Object.keys(statuses) as Status[],
    "OPEN",
  );
  return (
    <section className="sub-panel" aria-labelledby="site-applications">
      <div className="panel-heading">
        <h2 id="site-applications">创作者申请</h2>
      </div>
      <p className="muted">
        批准后账号成为创作者，可以新建空间和公开网站，并记入策略组变更记录。拒绝需要写明原因，申请人能看到，7
        天后可以再次申请。
      </p>
      <div className="filter-chips" role="group" aria-label="申请状态">
        {(Object.keys(statuses) as Status[]).map((key) => (
          <button
            key={key}
            type="button"
            aria-pressed={status === key}
            onClick={() =>
              navigateSite({ applicationStatus: key, applicationOffset: 0 })
            }
          >
            {statuses[key]}
          </button>
        ))}
      </div>
      <Applications key={status} status={status} />
    </section>
  );
}

function Applications({ status }: { status: Status }) {
  const location = useSiteLocation();
  const page = useAdminPage<Queued>(
    "/api/auth/site/creator-applications?status=" + status,
    {
      offset: pageOffset(location, "applicationOffset"),
      setOffset: (offset) => navigateSite({ applicationOffset: offset }),
    },
  );
  return (
    <>
      {page.error && (
        <p role="alert" className="notice danger">
          {page.error}
        </p>
      )}
      {!page.loading && !page.error && page.items.length === 0 && (
        <p className="muted">没有{statuses[status]}的申请。</p>
      )}
      <ul className="admin-list applications">
        {!page.loading &&
          !page.error &&
          page.items.map((item) => (
            <li key={item.application.id}>
              <Application item={item} onDecided={page.reload} />
            </li>
          ))}
      </ul>
      <AdminPagination label="创作者申请" page={page} />
    </>
  );
}

function Application({
  item,
  onDecided,
}: {
  item: Queued;
  onDecided: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const { application } = item;
  const noteField = useRef<HTMLTextAreaElement>(null);
  async function decide(approve: boolean) {
    const note = noteField.current?.value ?? "";
    if (!approve && !note.trim()) {
      setError("拒绝申请需要写明原因。");
      return;
    }
    setPending(true);
    setError("");
    try {
      await api(
        `/api/auth/site/creator-applications/${application.id}/decision`,
        { method: "POST", body: { approve, note } },
      );
      onDecided();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  return (
    <article className="application-item">
      <header className="admin-list-main">
        <strong>{item.displayName}</strong>
        <span className="group-badge">{siteGroups[item.group]}</span>
        <small className="muted">
          {item.loginName} · {date(application.createdAt)}
        </small>
      </header>
      <p className="application-statement">{application.statement}</p>
      {application.status === "OPEN" ? (
        <div className="stack-form">
          <label>
            给申请人的说明（拒绝时必填）
            <textarea
              ref={noteField}
              name="note"
              maxLength={500}
              rows={2}
              disabled={pending}
            />
          </label>
          <div className="button-row">
            <button
              type="button"
              disabled={pending}
              onClick={() => void decide(true)}
            >
              批准<span className="sr-only"> {item.displayName}</span>
            </button>
            <button
              type="button"
              className="button-secondary"
              disabled={pending}
              onClick={() => void decide(false)}
            >
              拒绝<span className="sr-only"> {item.displayName}</span>
            </button>
          </div>
        </div>
      ) : (
        <p className="muted">
          {statuses[application.status]}
          {application.decidedAt && ` · ${date(application.decidedAt)}`}
          {application.decisionNote && `：${application.decisionNote}`}
        </p>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </article>
  );
}
