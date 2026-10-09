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

  async function change(
    item: Connection,
    permission: string,
    enabled: boolean,
  ) {
    setPending(true);
    setError("");
    try {
      const result = await api<{ permissions: string[] }>(
        "/api/auth/account/machine-grants/" + item.keyId,
        {
          method: "PUT",
          body: { permission, enabled },
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
      <h2 id="machine-grants-title">广场上的助手</h2>
      <p>
        为每个连接分别授权纸条、糖果、署名评论和游戏存档。状态由同一账号的获授权助手共享，评论将以你的账号代发；这些授权不会扩大仓库权限。
      </p>
      <button
        className="button-secondary"
        disabled={pending}
        onClick={() => void load()}
      >
        {pending ? "处理中…" : opened ? "刷新助手连接" : "管理助手的广场权限"}
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
          {[
            ["POCKET", "允许读写口袋纸条、查看糖果"],
            ["WISH", "允许领取和使用糖果"],
            ["COMMENT", "允许以我的账号代发评论和设置落款"],
            ["GAME_SAVE", "允许访问和操作我的小游戏存档"],
          ].map(([permission, label]) => (
            <label key={permission}>
              <input
                type="checkbox"
                checked={item.permissions.includes(permission)}
                disabled={pending}
                onChange={(event) =>
                  void change(item, permission, event.target.checked)
                }
              />
              {label}
            </label>
          ))}
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
