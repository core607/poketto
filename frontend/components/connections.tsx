"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";
import { scopeLabels } from "./connect";
import { useConfirmation } from "./confirmation";
type Connection = {
  id: string;
  clientName: string;
  scopes: string[];
  expiresAt: string;
  revoked: boolean;
  requiresReauthorization: boolean;
};
export function Connections() {
  const [items, setItems] = useState<Connection[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const confirm = useConfirmation();
  async function load() {
    try {
      setItems(
        (await api<{ items: Connection[] }>("/api/admin/connections")).items,
      );
      setError("");
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? "此实例尚未启用应用授权。"
          : message(e),
      );
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);
  async function disconnect(item: Connection) {
    if (
      !(await confirm({
        title: `断开 ${item.clientName}？`,
        description: "该连接的访问和自动续期将失效；其他应用连接不受影响。",
        confirmLabel: "断开连接",
      }))
    )
      return;
    setPending(true);
    try {
      await api("/api/admin/connections/" + item.id, { method: "DELETE" });
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setPending(false);
    }
  }
  return (
    <section>
      <h2>已连接应用</h2>
      <p>查看授予应用的权限，或撤销整个连接。</p>
      {loading && <p role="status">正在读取连接…</p>}
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {!loading && !error && items.length === 0 && <p>还没有应用连接。</p>}
      {items.map((item) => {
        const inactive =
          item.revoked || Date.parse(item.expiresAt) <= Date.now();
        return (
          <article className="connection-card" key={item.id}>
            <h3>{item.clientName}</h3>
            <p>
              {item.scopes.map((s) => scopeLabels[s]?.label ?? s).join("、")}
            </p>
            <p>
              {item.revoked
                ? "已断开"
                : inactive
                  ? "已过期"
                  : item.requiresReauthorization
                    ? "需重新授权"
                    : "已授权"}{" "}
              · 有效至 {new Date(item.expiresAt).toLocaleDateString("zh-CN")}
            </p>
            <button
              className="button-secondary"
              disabled={inactive || pending}
              onClick={() => void disconnect(item)}
            >
              断开连接
            </button>
          </article>
        );
      })}
    </section>
  );
}
