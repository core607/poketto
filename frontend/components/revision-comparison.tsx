"use client";

import { useState } from "react";
import { dateTime } from "../lib/format";
import { sourceDifference } from "../lib/source-diff";

type Version = { savedAt: string; body: string };

/** Compares two public versions line by line; versions arrive oldest first. */
export function RevisionComparison({ versions }: { versions: Version[] }) {
  const newest = versions.length - 1;
  const [before, setBefore] = useState(Math.max(newest - 1, 0));
  const [after, setAfter] = useState(newest);
  // The diff always runs from the earlier to the later version, whichever box holds which, so a
  // removed passage can never be shown as added.
  const [older, newer] = before <= after ? [before, after] : [after, before];
  const difference = sourceDifference(
    versions[older].body,
    versions[newer].body,
  );
  const label = (index: number) =>
    `第 ${index + 1} 版 · ${dateTime(versions[index].savedAt)}`;
  const choice = (
    name: string,
    value: number,
    change: (value: number) => void,
  ) => (
    <label>
      {name}
      <select
        value={value}
        onChange={(event) => change(Number(event.target.value))}
      >
        {versions.map((version, index) => (
          <option key={index} value={index}>
            {label(index)}
          </option>
        ))}
      </select>
    </label>
  );
  return (
    <section className="revision-comparison" aria-label="版本比较">
      <div className="revision-choices">
        {choice("较早", before, setBefore)}
        {choice("较新", after, setAfter)}
        {before > after && (
          <p className="muted">
            已按时间先后比较：从第 {older + 1} 版到第 {newer + 1} 版。
          </p>
        )}
      </div>
      {difference.kind === "unchanged" && <p>两个版本的正文相同。</p>}
      {difference.kind === "lines" && (
        <pre className="history-diff" aria-label="正文差异">
          {difference.lines.map((line, index) => (
            <span key={index} className={"history-line " + line.kind}>
              <span aria-hidden="true">
                {line.kind === "removed"
                  ? "− "
                  : line.kind === "added"
                    ? "+ "
                    : "  "}
              </span>
              {line.text.replace(/\r?\n$/, "")}
            </span>
          ))}
        </pre>
      )}
      {difference.kind === "side-by-side" && (
        <>
          <p>正文较长，改为并排显示两个版本。</p>
          <div className="history-sources">
            <section>
              <h2>{label(older)}</h2>
              <pre>{versions[older].body}</pre>
            </section>
            <section>
              <h2>{label(newer)}</h2>
              <pre>{versions[newer].body}</pre>
            </section>
          </div>
        </>
      )}
    </section>
  );
}
