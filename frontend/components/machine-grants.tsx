"use client";

import { useState } from "react";
import { api, message } from "../lib/browser-api";

type Connection = {
  keyId: string;
  workspaceName: string;
  clientName: string;
  permissions: string[];
};

export function MachineGrants() {
  const [items, setItems] = useState<Connection[]>([]);
  const [opened, setOpened] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [nextOffset, setNextOffset] = useState<number | null>(null);

  async function load(offset = 0) {
    setPending(true);
    setError("");
    try {
      const result = await api<{
        items: Connection[];
        nextOffset: number | null;
      }>("/api/auth/account/machine-grants?offset=" + offset);
      setItems((current) =>
        offset === 0 ? result.items : [...current, ...result.items],
      );
      setNextOffset(result.nextOffset);
      setOpened(true);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }

  async function change(item: Connection, enabled: boolean) {
    setPending(true);
    setError("");
    const permissions = item.permissions.filter((value) => value !== "POCKET");
    if (enabled) permissions.push("POCKET");
    try {
      const result = await api<{ permissions: string[] }>(
        "/api/auth/account/machine-grants/" + item.keyId,
        {
          method: "PUT",
          body: { permissions },
        },
      );
      setItems((current) =>
        current.map((value) =>
          value.keyId === item.keyId
            ? { ...value, permissions: result.permissions }
            : value,
        ),
      );
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="sub-panel" aria-labelledby="machine-grants-title">
      <h2 id="machine-grants-title">广场上的口袋</h2>
      <p>
        允许指定助手读取和写入你的口袋纸条。纸条属于账号，可由获授权的助手跨会话共用；这不会授予仓库权限。
      </p>
      <button
        className="button-secondary"
        disabled={pending}
        onClick={() => void load()}
      >
        {pending ? "正在读取…" : opened ? "刷新助手连接" : "管理助手的口袋权限"}
      </button>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {opened && items.length === 0 && <p>还没有有效的助手连接或 API 密钥。</p>}
      {items.map((item) => (
        <article className="connection-card" key={item.keyId}>
          <h3>{item.clientName}</h3>
          <p>
            {item.workspaceName} · {item.keyId.slice(0, 8)}
          </p>
          <label>
            <input
              type="checkbox"
              checked={item.permissions.includes("POCKET")}
              disabled={pending}
              onChange={(event) => void change(item, event.target.checked)}
            />
            允许访问我的口袋纸条
          </label>
        </article>
      ))}
      {nextOffset !== null && (
        <button disabled={pending} onClick={() => void load(nextOffset)}>
          更多连接
        </button>
      )}
    </section>
  );
}
