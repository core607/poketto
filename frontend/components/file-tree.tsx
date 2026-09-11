"use client";
import { useWorkspaceApi } from "./workspace-context";
import { useEffect, useRef, useState } from "react";
import { readDirectory, movablePath } from "../lib/repository-directory";
import type { RepositoryDirectory } from "../lib/types";
import { message } from "./admin";

type Props = {
  commit: string | null;
  selected?: string;
  filter: string;
  busy: boolean;
  onOpen: (path: string) => void;
  onMove?: (path: string, commit: string, trigger: HTMLElement) => void;
  onExport?: (path: string, trigger: HTMLElement) => void;
};

function exportablePath(path: string) {
  return !path
    .split("/")
    .some((part) => part.startsWith(".") || part.toUpperCase() === "AGENTS.MD");
}

export function FileTree(props: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  useEffect(() => {
    if (!props.selected) return;
    setExpanded((current) => {
      const next = new Set(current);
      const segments = props.selected!.split("/");
      for (let i = 1; i < segments.length; i++)
        next.add(segments.slice(0, i).join("/"));
      return next;
    });
  }, [props.selected]);
  return (
    <DirectoryBranch
      key={props.commit ?? "empty"}
      {...props}
      path=""
      expanded={expanded}
      onExpanded={(path, opened) =>
        setExpanded((current) => {
          if (current.has(path) === opened) return current;
          const next = new Set(current);
          if (opened) next.add(path);
          else next.delete(path);
          return next;
        })
      }
    />
  );
}

function DirectoryBranch({
  path,
  ...props
}: Props & {
  path: string;
  expanded: Set<string>;
  onExpanded: (path: string, opened: boolean) => void;
}) {
  const opened = !path || props.expanded.has(path);
  const api = useWorkspaceApi();
  const [page, setPage] = useState<RepositoryDirectory | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const pending = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  async function load(offset = 0) {
    if (pending.current) return;
    pending.current = true;
    setLoading(true);
    setError("");
    try {
      const result = await readDirectory(api, 
        page?.commit ?? props.commit,
        path,
        offset,
      );
      if (alive.current)
        setPage((current) => ({
          ...result,
          entries: offset
            ? [...(current?.entries ?? []), ...result.entries]
            : result.entries,
        }));
    } catch (error) {
      if (alive.current) setError(message(error));
    } finally {
      pending.current = false;
      if (alive.current) setLoading(false);
    }
  }
  useEffect(() => {
    if (opened && !page) void load();
  }, [opened]);
  const content = (
    <>
      {page?.entries
        .filter(
          (entry) =>
            entry.kind === "DIRECTORY" || entry.path.includes(props.filter),
        )
        .map((entry) =>
          entry.kind === "DIRECTORY" ? (
            <DirectoryBranch
              key={entry.path}
              {...props}
              commit={page.commit}
              path={entry.path}
            />
          ) : (
            <div className="tree-file-row" key={entry.path}>
              <button
                type="button"
                className={props.selected === entry.path ? "selected" : ""}
                title={entry.path}
                disabled={props.busy || entry.kind !== "FILE"}
                onClick={() => props.onOpen(entry.path)}
              >
                <span aria-hidden>{entry.kind === "FILE" ? "▤" : "↗"}</span>
                <span>{entry.path.split("/").at(-1)}</span>
              </button>
              {entry.kind === "FILE" &&
                props.onMove &&
                movablePath(entry.path) &&
                page.commit && (
                  <button
                    type="button"
                    className="tree-move"
                    aria-label={`移动 ${entry.path}`}
                    title="移动"
                    disabled={props.busy}
                    onClick={(event) =>
                      props.onMove!(
                        entry.path,
                        page.commit!,
                        event.currentTarget,
                      )
                    }
                  >
                    移动
                  </button>
                )}
              {entry.kind === "FILE" &&
                props.onExport &&
                exportablePath(entry.path) && (
                  <button
                    type="button"
                    className="tree-move"
                    aria-label={`导出 ${entry.path}`}
                    disabled={props.busy}
                    onClick={(event) =>
                      props.onExport!(entry.path, event.currentTarget)
                    }
                  >
                    导出
                  </button>
                )}
            </div>
          ),
        )}
      {loading && (
        <p className="muted" role="status">
          正在读取目录…
        </p>
      )}
      {error && (
        <p role="alert">
          {error}{" "}
          <button
            type="button"
            onClick={() => void load(page?.nextOffset ?? 0)}
          >
            重试
          </button>
        </p>
      )}
      {page?.nextOffset != null && !error && (
        <button
          type="button"
          disabled={loading || props.busy}
          onClick={() => void load(page.nextOffset!)}
        >
          加载更多
        </button>
      )}
      {page && !page.entries.length && <p className="muted">空目录</p>}
    </>
  );
  if (!path) return content;
  return (
    <details
      className="tree-directory"
      open={opened}
      onToggle={(event) => props.onExpanded(path, event.currentTarget.open)}
    >
      <summary>{path.split("/").at(-1)}</summary>
      {props.onMove && props.commit && movablePath(path) && (
        <button
          className="tree-move"
          type="button"
          disabled={props.busy}
          aria-label={`移动文件夹 ${path}`}
          onClick={(event) =>
            props.onMove!(path, props.commit!, event.currentTarget)
          }
        >
          移动文件夹
        </button>
      )}
      <div>{opened && content}</div>
      {opened && props.onExport && exportablePath(path) && (
        <button
          type="button"
          className="tree-move"
          aria-label={`导出文件夹 ${path}`}
          disabled={props.busy}
          onClick={(event) => props.onExport!(path, event.currentTarget)}
        >
          导出文件夹
        </button>
      )}
    </details>
  );
}
