import type { ReactNode } from "react";
import { Icon } from "./icons";

/** Previous/next navigation that renders nothing when there is only one page. */
export function Pager({
  label,
  previous,
  next,
  status,
  end,
}: {
  label: string;
  previous?: string | null;
  next?: string | null;
  status?: string;
  end?: ReactNode;
}) {
  if (!previous && !next && !end) return null;
  return (
    <nav className="pager" aria-label={label}>
      {previous && (
        <a className="btn btn-secondary" href={previous}>
          <Icon name="arrowLeft" />
          上一页
        </a>
      )}
      {status && (previous || next) && (
        <span className="pager-status">{status}</span>
      )}
      {next && (
        <a className="btn btn-secondary" href={next}>
          下一页
          <Icon name="arrowRight" />
        </a>
      )}
      {!next && end}
    </nav>
  );
}
