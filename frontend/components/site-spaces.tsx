"use client";
import { useState } from "react";
import { date } from "../lib/format";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { SiteReview, SpaceState } from "./site-review";
import { useModal } from "./use-modal";

export type SpaceFilter = "ALL" | "PUBLIC" | "RESTRICTED" | "CLOSED";
const filters: Record<SpaceFilter, string> = {
  ALL: "全部",
  PUBLIC: "公开中",
  RESTRICTED: "展示受限",
  CLOSED: "网站关闭",
};
type Space = {
  workspaceId: string;
  slug: string | null;
  displayName: string;
  enabled: boolean;
  eligible: boolean;
  createdAt: string;
  owners: string[];
  ownerCount: number;
};

/** Every space on the site, filtered by website state, with public-content review. */
export function SiteSpaces({
  initialState = "ALL",
}: {
  initialState?: SpaceFilter;
}) {
  const [state, setState] = useState<SpaceFilter>(initialState);
  const [query, setQuery] = useState("");
  return (
    <section className="sub-panel" aria-labelledby="site-spaces">
      <div className="panel-heading">
        <h2 id="site-spaces">全部空间</h2>
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
          搜索空间名称或地址
          <input name="query" maxLength={254} type="search" />
        </label>
        <button>搜索</button>
      </form>
      <div className="filter-chips" role="group" aria-label="网站状态">
        {(Object.keys(filters) as SpaceFilter[]).map((key) => (
          <button
            key={key}
            type="button"
            aria-pressed={state === key}
            onClick={() => setState(key)}
          >
            {filters[key]}
          </button>
        ))}
      </div>
      <Spaces key={state + "\n" + query} state={state} query={query} />
    </section>
  );
}

function Spaces({ state, query }: { state: SpaceFilter; query: string }) {
  const page = useAdminPage<Space>(
    `/api/auth/site/workspaces?state=${state}&query=${encodeURIComponent(query)}`,
  );
  const [selected, setSelected] = useState<{
    space: Space;
    trigger: HTMLElement | null;
  } | null>(null);
  return (
    <>
      {page.error && (
        <p role="alert" className="notice danger">
          {page.error}
        </p>
      )}
      {!page.loading && !page.error && page.items.length === 0 && (
        <p className="muted">没有符合条件的空间。</p>
      )}
      <ul className="admin-list">
        {!page.loading &&
          !page.error &&
          page.items.map((space) => (
            <li key={space.workspaceId}>
              <span className="admin-list-main">
                <strong>{space.displayName}</strong>
                <SpaceState space={space} />
                <small className="muted">
                  {space.slug ? `/s/${space.slug}` : "未设置地址"} · 所有者：
                  {space.owners.join("、") || "无"}
                  {space.ownerCount > space.owners.length &&
                    ` 等 ${space.ownerCount} 人`}{" "}
                  · 创建于 {date(space.createdAt)}
                </small>
              </span>
              <button
                type="button"
                className="button-secondary"
                onClick={(event) =>
                  setSelected({ space, trigger: event.currentTarget })
                }
              >
                审阅<span className="sr-only"> {space.displayName}</span>
              </button>
            </li>
          ))}
      </ul>
      <AdminPagination label="全部空间" page={page} />
      {selected && (
        <ReviewDialog
          key={selected.space.workspaceId}
          space={selected.space}
          returnFocus={selected.trigger}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}

function ReviewDialog({
  space,
  returnFocus,
  onClose,
}: {
  space: Space;
  returnFocus: HTMLElement | null;
  onClose: () => void;
}) {
  const dialog = useModal(returnFocus);
  return (
    <dialog
      ref={dialog}
      className="side-dialog"
      aria-label={`审阅 ${space.displayName}`}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <SiteReview space={space} onClose={onClose} />
    </dialog>
  );
}
