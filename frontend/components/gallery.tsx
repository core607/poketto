import type { GalleryStatus } from "../lib/types";
import { safeImage } from "../lib/format";
export function Gallery({
  items = [],
  preview = false,
  status = "COMPLETE",
}: {
  items?: { src: string; alt: string }[];
  preview?: boolean;
  status?: GalleryStatus;
}) {
  const safe = items.filter((item) => safeImage(item.src, preview));
  if (!safe.length && status === "COMPLETE") return null;
  return (
    <section className="gallery" aria-label="同目录图片">
      {status !== "COMPLETE" && (
        <p className="gallery-notice" role="status">
          {status === "PARTIAL"
            ? "部分同目录图片未展示。"
            : "同目录图片暂时无法加载。"}
        </p>
      )}
      {safe.map((item, index) => (
        <figure key={index}>
          <img src={item.src} alt={item.alt} loading="lazy" decoding="async" />
          {item.alt && <figcaption>{item.alt}</figcaption>}
        </figure>
      ))}
    </section>
  );
}
