"use client";

import { useEffect, useRef, useState } from "react";
import { api, ApiError, message } from "../lib/browser-api";
import {
  sameGame,
  type GamePackage,
  type GameSave,
  type GameSaveIndex,
  type GameUpload,
} from "../lib/games";
import { LoginDialog } from "./login-dialog";

const path = "/api/games/saves";
const statusLabels: Record<string, string> = {
  READY: "可接手",
  GAME_UPDATED: "游戏已更新",
  GAME_UNAVAILABLE: "游戏不可用",
  GAME_BUSY: "正在进行",
  GAME_FAILED: "启动未完成",
};

/** Only explicit account controls call these APIs; iframe messages never save or delete progress. */
export function GameSaves({
  space,
  game,
  hasState,
  snapshot,
  resume,
}: {
  space: string;
  game: GamePackage;
  hasState: boolean;
  snapshot: () => unknown;
  resume: (state: unknown) => void;
}) {
  const [index, setIndex] = useState<GameSaveIndex | null>(null);
  const [selected, setSelected] = useState<Pick<
    GameSave,
    "saveId" | "revision"
  > | null>(null);
  const [busy, setBusy] = useState(false);
  const [login, setLogin] = useState(false);
  const [notice, setNotice] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const pending = useRef<GameUpload | null>(null);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function operation(action: () => Promise<void>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setNotice("");
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setLogin(true);
      setNotice(message(error));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function refresh() {
    const value = await api<GameSaveIndex>(path);
    if (index && index.accountId !== value.accountId) {
      setSelected(null);
      pending.current = null;
      setUncertain(false);
    }
    setIndex(value);
    setLogin(false);
    return value;
  }

  async function upload(newSave: boolean) {
    if (!pending.current) {
      const state = snapshot();
      if (state === undefined || !index) return;
      pending.current = {
        accountId: index.accountId,
        saveId: newSave ? null : (selected?.saveId ?? null),
        creationRequest:
          newSave || !selected ? index.nextCreationRequest : null,
        expectedRevision: newSave ? null : (selected?.revision ?? null),
        space,
        articleId: game.articleId,
        packageVersion: game.version,
        state: JSON.parse(JSON.stringify(state)),
      };
    }
    try {
      const created = pending.current.saveId === null;
      const saved = await api<GameSave>(path, {
        method: "POST",
        body: pending.current,
      });
      pending.current = null;
      setUncertain(false);
      // A replayed creation may return a save that an assistant has already advanced.
      // Keep the browser's original base until the user explicitly loads that newer state.
      setSelected({
        saveId: saved.saveId,
        revision: created ? "1" : saved.revision,
      });
      setNotice(
        created && saved.revision !== "1"
          ? "原存档已保存，云端随后又有了新进度。请载入最新存档，或将页面中的进度另存为新局。"
          : `已保存到账号，进度版本 ${saved.revision}。获授权的助手可以接手。`,
      );
    } catch (error) {
      const rejected =
        error instanceof ApiError && error.status >= 400 && error.status < 500;
      if (rejected) {
        pending.current = null;
        setUncertain(false);
        if (error.code === "SAVE_CONFLICT") {
          setNotice(
            "账号或云端进度已经变化。请刷新列表，载入最新进度，或将当前进度另存为新局。",
          );
          return;
        }
        throw error;
      }
      setUncertain(true);
      setNotice(
        "保存结果尚未确认。可以核对云端列表，或重试同一份进度；重试不会重复创建或重复覆盖。",
      );
      return;
    }
    await refresh();
  }

  async function load(id: string) {
    const saved = await api<GameSave>(`${path}/${id}`);
    if (!mounted.current) return;
    if (!sameGame(saved, game) || saved.packageVersion !== game.version) {
      setNotice("存档对应另一版本的游戏，无法在这里接手。");
      return;
    }
    setSelected(saved);
    resume(saved.result.state);
    setNotice(`已载入版本 ${saved.revision}。后续进度需要手动保存。`);
  }

  async function remove(id: string) {
    await api(`${path}/${id}`, { method: "DELETE" });
    if (selected?.saveId === id) setSelected(null);
    setRemoving(null);
    await refresh();
    setNotice("存档已删除，当前页面的进度仍然保留。");
  }

  return (
    <section aria-label="账号存档">
      <p>登录后可保存到账号，与获授权的助手接手同一局。不会自动保存。</p>
      {login ? (
        <LoginDialog
          label="登录以管理存档"
          onLogin={() =>
            operation(async () => {
              await refresh();
            })
          }
        />
      ) : (
        <button
          className="btn btn-secondary"
          disabled={busy}
          onClick={() =>
            void operation(async () => {
              await refresh();
            })
          }
        >
          {index ? "刷新云端存档" : "管理账号存档"}
        </button>
      )}
      {index && (
        <>
          <p>
            {selected
              ? `当前存档：${selected.saveId} · 版本 ${selected.revision}`
              : "当前进度尚未关联云端存档。"}
          </p>
          <button
            className="btn btn-primary"
            disabled={busy || !hasState || uncertain}
            onClick={() => void operation(() => upload(false))}
          >
            保存当前进度
          </button>
          {selected && (
            <button
              className="btn btn-secondary"
              disabled={busy || !hasState || uncertain}
              onClick={() => void operation(() => upload(true))}
            >
              另存为新局
            </button>
          )}
          {uncertain && (
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={() => void operation(() => upload(false))}
            >
              重试同一份进度
            </button>
          )}
          {index.items.length === 0 && <p>账号里还没有存档。</p>}
          <ul>
            {index.items.map((item) => (
              <li key={item.saveId}>
                <p>
                  {item.title || "不可用的游戏"} · {item.saveId} · 版本{" "}
                  {item.revision} · {statusLabels[item.status] ?? "不可用"}
                </p>
                {sameGame(item, game) && item.status === "READY" && (
                  <button
                    className="btn btn-secondary"
                    disabled={busy || uncertain}
                    onClick={() => void operation(() => load(item.saveId))}
                  >
                    载入并替换当前进度
                  </button>
                )}
                {removing === item.saveId ? (
                  <>
                    <button
                      className="btn btn-secondary"
                      disabled={busy || uncertain}
                      onClick={() => void operation(() => remove(item.saveId))}
                    >
                      确认删除这份存档
                    </button>
                    <button
                      className="btn btn-secondary"
                      disabled={busy}
                      onClick={() => setRemoving(null)}
                    >
                      取消
                    </button>
                  </>
                ) : (
                  <button
                    className="btn btn-secondary"
                    disabled={busy || uncertain}
                    onClick={() => setRemoving(item.saveId)}
                  >
                    删除存档
                  </button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
      <p role="status">{busy ? "正在处理存档…" : notice}</p>
    </section>
  );
}
