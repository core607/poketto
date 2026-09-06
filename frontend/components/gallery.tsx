import { safeImage } from "../lib/format";
export function Gallery({
  items = [],
  preview = false,
}: {
  items?: { src: string; alt: string }[];
  preview?: boolean;
}) {
  const safe = items.filter((item) => safeImage(item.src, preview));
  if (!safe.length) return null;
  return (
    <section className="gallery" aria-label="同目录图片">
      {safe.map((item, index) => (
        <figure key={index}>
          <img src={item.src} alt={item.alt} loading="lazy" decoding="async" />
          {item.alt && <figcaption>{item.alt}</figcaption>}
        </figure>
      ))}
    </section>
  );
}
