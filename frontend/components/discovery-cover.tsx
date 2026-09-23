"use client";

import { useState } from "react";
import { safeImage } from "../lib/format";

export function DiscoveryCover({
  src,
  href,
  title,
  album = true,
}: {
  src: string | null;
  href: string;
  title: string;
  /** Album covers name the album; article covers show the article's first image. */
  album?: boolean;
}) {
  const image = safeImage(src ?? undefined);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  return (
    <a
      className="card-cover"
      href={href}
      aria-label={`${album ? "打开相册" : "阅读"}：${title}`}
    >
      {image && failedSource !== image ? (
        <img
          src={image}
          alt=""
          loading="lazy"
          decoding="async"
          onError={() => setFailedSource(image)}
        />
      ) : (
        <span className="card-cover-empty">
          {album ? "封面暂时不可用 · 打开相册" : "图片暂时不可用"}
        </span>
      )}
    </a>
  );
}
