"use client";
import { useEffect, useRef, useState } from "react";
import type { SpaceSummary } from "./workspace-dashboard";
import { Avatar, Icon } from "./ui/icons";

type SpacePage = {
  items: SpaceSummary[];
  total: number;
  offset: number;
  limit: number;
  loading: boolean;
  error: string;
  reload: () => void;
  setOffset: (offset: number) => void;
};

/** The studio's current space, with a filterable list of the account's spaces. */
export function SpaceSwitcher({
  page,
  selected,
  current,
  disabled,
  onSelect,
  onManage,
}: {
  page: SpacePage;
  selected: string;
  current?: SpaceSummary | { displayName: string; role?: string };
  disabled: boolean;
  onSelect: (workspaceId: string) => void;
  onManage: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);
  const visible = page.items.filter((space) =>
    space.displayName.toLowerCase().includes(filter.trim().toLowerCase()),
  );
  const name = current?.displayName ?? "选择空间";
  return (
    <div className="space-switcher" ref={root}>
      <button
        type="button"
        className="space-switcher-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`当前空间：${name}，切换空间`}
        disabled={disabled}
        onClick={() => setOpen(!open)}
      >
        <Avatar name={name} />
        <span className="space-switcher-name">
          <strong>{name}</strong>
          <small>
            {!selected
              ? "未选择空间"
              : current && "role" in current && current.role === "OWNER"
                ? "所有者"
                : "成员"}
          </small>
        </span>
        <Icon name="chevronDown" />
      </button>
      {open && (
        <div className="space-switcher-panel">
          {page.total > 6 && (
            <input
              className="input"
              type="search"
              placeholder="筛选空间"
              aria-label="筛选空间"
              value={filter}
              autoFocus
              onChange={(event) => setFilter(event.target.value)}
            />
          )}
          <ul role="listbox" aria-label="我的空间">
            {visible.map((space) => (
              <li key={space.workspaceId}>
                <button
                  type="button"
                  role="option"
                  aria-selected={space.workspaceId === selected}
                  onClick={() => {
                    setOpen(false);
                    if (space.workspaceId !== selected)
                      onSelect(space.workspaceId);
                  }}
                >
                  <Avatar name={space.displayName} />
                  <span>{space.displayName}</span>
                  {space.workspaceId === selected && <Icon name="check" />}
                </button>
              </li>
            ))}
            {!visible.length && !page.loading && (
              <li className="muted space-switcher-empty">
                {page.total ? "没有匹配的空间" : "还没有空间"}
              </li>
            )}
          </ul>
          {(page.offset > 0 || page.offset + page.limit < page.total) && (
            <div className="space-switcher-pages">
              <button
                type="button"
                className="link-btn"
                disabled={page.loading || page.offset === 0}
                onClick={() =>
                  page.setOffset(Math.max(0, page.offset - page.limit))
                }
              >
                上一页
              </button>
              <button
                type="button"
                className="link-btn"
                disabled={
                  page.loading || page.offset + page.limit >= page.total
                }
                onClick={() => page.setOffset(page.offset + page.limit)}
              >
                更多空间
              </button>
            </div>
          )}
          <button
            type="button"
            className="space-switcher-manage"
            onClick={() => {
              setOpen(false);
              onManage();
            }}
          >
            <Icon name="plus" />
            创建或加入空间
          </button>
        </div>
      )}
      {page.error && (
        <p className="studio-side-error">
          空间列表暂时无法读取，你的账号仍已登录。
          <button type="button" className="link-btn" onClick={page.reload}>
            重新读取空间
          </button>
        </p>
      )}
    </div>
  );
}
