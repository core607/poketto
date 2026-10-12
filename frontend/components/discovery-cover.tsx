"use client";

import { useState } from "react";
import type { CoverFace } from "../lib/cover-face";
import { safeImage } from "../lib/format";

export function DiscoveryCover({
  src,
  href,
  title,
  face,
  album = true,
}: {
  src: string | null;
  href: string;
  title: string;
  /** The typeset cover shown when there is no image or the image fails to load. */
  face: CoverFace;
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
        <TypesetCover face={face} />
      )}
    </a>
  );
}

function TypesetCover({ face }: { face: CoverFace }) {
  const layout = face.lead
    ? face.vertical
      ? "vertical"
      : "quote"
    : face.label
      ? "seal"
      : "mark";
  return (
    <span
      className="cover-face"
      data-layout={layout}
      style={{ "--hue": face.hue } as React.CSSProperties}
      aria-hidden
    >
      {face.lead ? (
        <>
          <span
            className="cover-lead"
            data-short={[...face.lead].length <= 36 || undefined}
          >
            {face.lead}
          </span>
          <span className="cover-space">{face.spaceName}</span>
        </>
      ) : (
        <>
          {face.label && <span className="cover-label">{face.label}</span>}
          <span className="cover-seal">{face.seal}</span>
        </>
      )}
    </span>
  );
}
