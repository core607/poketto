"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, message } from "../lib/browser-api";
import { date } from "../lib/format";
import type { SiteGroup } from "../lib/site-groups";

type Application = {
  id: string;
  statement: string;
  status: "OPEN" | "APPROVED" | "DECLINED";
  createdAt: string;
  decidedAt: string | null;
  decisionNote: string | null;
};
type Standing = {
  group: SiteGroup;
  latest: Application | null;
  availableAt: string | null;
};

/** A viewer or community member asks a site administrator for creator eligibility. */
export function CreatorApplication() {
  const [standing, setStanding] = useState<Standing | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<Standing>("/api/auth/creator-application")
      .then((value) => {
        if (active) setStanding(value);
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    return () => {
      active = false;
    };
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const statement = String(
      new FormData(event.currentTarget).get("statement") ?? "",
    );
    setPending(true);
    setError("");
    try {
      const latest = await api<Application>("/api/auth/creator-application", {
        method: "POST",
        body: { statement },
      });
      setStanding((value) => value && { ...value, latest, availableAt: null });
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  if (!standing)
    return error ? (
      <p role="alert" className="notice danger">
        {error}
      </p>
    ) : (
      <p role="status" className="muted">
        正在读取申请状态…
      </p>
    );
  const { latest, availableAt } = standing;
  const waiting = availableAt && new Date(availableAt).getTime() > Date.now();
  const canApply = latest?.status !== "OPEN" && !waiting;
  return (
    <div className="creator-application">
      {latest?.status === "OPEN" && (
        <p className="notice" role="status">
          你在 {date(latest.createdAt)}{" "}
          提交的申请正在等待站点管理员审核。审核通过后，你就可以新建自己的空间。
        </p>
      )}
      {latest?.status === "DECLINED" && (
        <div className="notice">
          <p>
            你在 {date(latest.createdAt)} 提交的申请未通过
            {latest.decisionNote ? `：${latest.decisionNote}` : "。"}
          </p>
          {waiting && <p>{date(availableAt)} 之后可以再次申请。</p>}
        </div>
      )}
      {canApply && (
        <form onSubmit={submit} className="stack-form">
          <label>
            说说你想用空间做什么
            <textarea
              name="statement"
              required
              maxLength={1000}
              rows={4}
              placeholder="例如：整理读书笔记并公开成一个小网站。"
              disabled={pending}
            />
          </label>
          <div className="button-row">
            <button disabled={pending}>
              {pending ? "正在提交…" : "申请成为创作者"}
            </button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </div>
  );
}
