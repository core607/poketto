"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../lib/browser-api";
import { gamePackageUrl, type GamePackage } from "../lib/games";
import { GameSaves } from "./game-saves";

export function GamePlayer({
  space,
  articleId,
}: {
  space: string;
  articleId: string | null;
}) {
  const [game, setGame] = useState<GamePackage | null>(null);
  const [playing, setPlaying] = useState(false);
  const [message, setMessage] = useState("");
  const [hasState, setHasState] = useState(false);
  const [generation, setGeneration] = useState(0);
  const frame = useRef<HTMLIFrameElement>(null);
  const channel = useRef<MessagePort | null>(null);
  const savedState = useRef<unknown>(undefined);

  useEffect(() => {
    let active = true;
    setGame(null);
    setPlaying(false);
    setHasState(false);
    savedState.current = undefined;
    if (articleId) {
      void api<GamePackage>(gamePackageUrl(space, articleId))
        .then((value) => {
          if (active) setGame(value);
        })
        .catch(() => {
          if (active) setGame(null);
        });
    }
    return () => {
      active = false;
      channel.current?.close();
      channel.current = null;
    };
  }, [space, articleId]);

  function connect() {
    if (!game || !frame.current?.contentWindow) return;
    channel.current?.close();
    const pipe = new MessageChannel();
    channel.current = pipe.port1;
    pipe.port1.onmessage = ({ data }) => {
      if (channel.current !== pipe.port1) return;
      if (data?.kind === "ready") {
        pipe.port1.postMessage({
          kind: "load",
          bundle: game.bundle,
          state: savedState.current,
        });
      } else if (data?.kind === "check") {
        // Public metadata checks start no jobs; every new local step requires a current package.
        void api<Pick<GamePackage, "version" | "workspaceId">>(
          gamePackageUrl(space, game.articleId) + "/status",
        )
          .then((current) => {
            if (channel.current === pipe.port1)
              pipe.port1.postMessage({
                kind: "checked",
                id: data.id,
                allowed:
                  current.version === game.version &&
                  current.workspaceId === game.workspaceId,
              });
          })
          .catch(() => {
            if (channel.current === pipe.port1)
              pipe.port1.postMessage({
                kind: "checked",
                id: data.id,
                allowed: false,
              });
          });
      } else if (data?.kind === "state") {
        const encoded = JSON.stringify(data.state);
        if (
          typeof encoded !== "string" ||
          new TextEncoder().encode(encoded).length > 32768
        ) {
          setMessage("游戏返回的进度过大，未保留这一步。");
          return;
        }
        savedState.current = JSON.parse(encoded);
        setHasState(true);
        setMessage("进度保留在当前页面。");
      } else if (data?.kind === "error") {
        setMessage("这一步没有完成，上一次进度仍然保留。");
      }
    };
    pipe.port1.start();
    // Opaque sandbox origins require '*'; the port goes only to this fixed frame.
    frame.current.contentWindow.postMessage(
      { kind: "poketto-game-connect" },
      "*",
      [pipe.port2],
    );
  }

  if (!game) return null;
  return (
    <section className="community-section" aria-label="小游戏">
      <h2>小游戏 · {game.title}</h2>
      <p>{game.help}</p>
      {!playing ? (
        <button
          className="btn btn-primary"
          onClick={() => {
            setPlaying(true);
            setMessage("");
          }}
        >
          {savedState.current === undefined ? "开始游戏" : "继续游戏"}
        </button>
      ) : (
        <>
          <iframe
            key={generation}
            ref={frame}
            title={`${game.title} 游戏画面`}
            src="/games/frame"
            sandbox="allow-scripts"
            referrerPolicy="no-referrer"
            onLoad={connect}
            style={{ width: "100%", height: "28rem", border: 0 }}
          />
          <button
            className="btn btn-secondary"
            onClick={() => {
              channel.current?.close();
              channel.current = null;
              setPlaying(false);
            }}
          >
            收起游戏
          </button>
        </>
      )}
      <p role="status">
        {message || "无需登录即可游玩。当前进度会在关闭页面后丢失。"}
      </p>
      <GameSaves
        key={`${space}/${game.articleId}/${game.version}`}
        space={space}
        game={game}
        hasState={hasState}
        snapshot={() => savedState.current}
        resume={(state) => {
          channel.current?.close();
          channel.current = null;
          savedState.current = state;
          setHasState(true);
          setGeneration((value) => value + 1);
          setPlaying(true);
          setMessage("正在载入账号中的进度…");
        }}
      />
    </section>
  );
}
