"use client";
import { useEffect, useState } from "react";
import { ApiError } from "../lib/browser-api";
import { useWorkspaceApi } from "./workspace-context";
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
  const api = useWorkspaceApi();
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
    <section className="sub-panel" aria-labelledby="connections-title">
      <div className="panel-heading">
        <div>
          <h2 id="connections-title">已连接的 AI 助手</h2>
          <p>每个连接能做什么、到什么时候有效；不需要了可以随时断开。</p>
        </div>
      </div>
      {loading && <p role="status">正在读取连接…</p>}
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {!loading && !error && items.length === 0 && (
        <p className="muted">还没有连接任何 AI 助手。</p>
      )}
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
