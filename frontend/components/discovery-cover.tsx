"use client";

import { useState } from "react";
import { safeImage } from "../lib/format";

export function DiscoveryCover({
  src,
  href,
  title,
}: {
  src: string | null;
  href: string;
  title: string;
}) {
  const image = safeImage(src ?? undefined);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  return (
    <a
      className="discovery-cover"
      href={href}
      aria-label={`打开相册：${title}`}
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
        <span>封面暂时不可用 · 打开相册 ↗</span>
      )}
    </a>
  );
}
