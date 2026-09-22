"use client";
import { useState, type ReactNode } from "react";

export function MediaPlayer({
  kind,
  src,
  download,
  children,
}: {
  kind: "audio" | "video";
  src: string;
  download: string;
  children: ReactNode;
}) {
  const [failed, setFailed] = useState(false);
  return (
    <span className="media-player">
      {kind === "audio" ? (
        <audio
          controls
          preload="none"
          src={src}
          aria-label="音频播放"
          onError={() => setFailed(true)}
        />
      ) : (
        <video
          controls
          playsInline
          preload="none"
          src={src}
          aria-label="视频播放"
          onError={() => setFailed(true)}
        />
      )}
      {failed && <span role="status">暂时无法播放，可尝试下载原文件。</span>}
      <a href={download} rel="noreferrer noopener">
        {children} · 下载原文件
      </a>
    </span>
  );
}
