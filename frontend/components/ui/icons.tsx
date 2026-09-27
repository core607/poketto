import type { SVGProps } from "react";

const paths = {
  search: "M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16Zm10 2-4.35-4.35",
  compass: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm3.5-12.5-2 5-5 2 2-5 5-2Z",
  heart:
    "M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z",
  bookmark: "M6 4h12v17l-6-4-6 4V4Z",
  bell: "M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15L6 16Zm4 4a2 2 0 0 0 4 0",
  user: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0",
  users:
    "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm-6 9a6 6 0 0 1 12 0m1-9a3 3 0 1 0-1-5.8M17 14.2A5.5 5.5 0 0 1 21 20",
  plus: "M12 5v14M5 12h14",
  pen: "M4 20h4L19 9l-4-4L4 16v4Zm9-13 4 4",
  arrowLeft: "M19 12H5m6-6-6 6 6 6",
  arrowRight: "M5 12h14m-6-6 6 6-6 6",
  external:
    "M14 5h5v5m0-5-8 8M18 14v4a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h4",
  chevronRight: "m9 6 6 6-6 6",
  chevronDown: "m6 9 6 6 6-6",
  shuffle:
    "M4 7h3.5c2 0 3.2 1 4.3 2.7l.4.6M4 17h3.5c2 0 3.2-1 4.3-2.7l2.4-3.6C15.3 9 16.5 8 18.5 8H20m-2.5-3L20 8l-2.5 3M20 16h-1.5c-1.6 0-2.7-.6-3.6-1.7M17.5 13 20 16l-2.5 3",
  rss: "M5 5a14 14 0 0 1 14 14M5 11a8 8 0 0 1 8 8M6 19h.01",
  logout: "M15 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3M10 16l4-4-4-4m4 4H4",
  settings:
    "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.4 7.4 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7.5 7.5 0 0 0-2-1.2L14.6 3h-4l-.4 2.6a7.5 7.5 0 0 0-2 1.2l-2.3-.9-2 3.4 2 1.5a7.4 7.4 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-.9a7.5 7.5 0 0 0 2 1.2l.4 2.6h4l.4-2.6a7.5 7.5 0 0 0 2-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2Z",
  shield: "M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6l-7-3Z",
  layers: "m12 4 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5",
  image: "M4 5h16v14H4V5Zm0 11 4.5-4.5 3.5 3.5 2.5-2.5L20 17M15.5 9.5h.01",
  folder:
    "M3 7a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z",
  file: "M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm7 0v5h5",
  globe:
    "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm-9-9h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3Z",
  link: "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1",
  branch:
    "M6 3v12m0 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm12-6a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 0c0 4-4 5-9 6",
  x: "M6 6l12 12M18 6 6 18",
  check: "m5 12 5 5 9-10",
  more: "M5 12h.01M12 12h.01M19 12h.01",
  menu: "M4 7h16M4 12h16M4 17h16",
  eye: "M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7Zm9.5 3a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
  lock: "M6 11h12v10H6V11Zm2 0V8a4 4 0 1 1 8 0v3",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v4l3 2",
  trash: "M4 7h16M10 11v6m4-6v6M6 7l1 13h10l1-13M9 7V4h6v3",
  upload: "M12 16V4m-5 5 5-5 5 5M5 20h14",
  share:
    "M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm12 7a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM8.6 13.5l6.8 4M15.4 6.5l-6.8 4",
  message: "M4 5h16v11H9l-5 4V5Z",
  sparkle:
    "M12 3v4m0 10v4M3 12h4m10 0h4M6 6l2.5 2.5m7 7L18 18M6 18l2.5-2.5m7-7L18 6",
  home: "M4 11 12 4l8 7v9h-5v-6h-6v6H4v-9Z",
  tag: "M3 12V4h8l9 9-8 8-9-9Zm5-4h.01",
  archive: "M4 5h16v4H4V5Zm1 4v10h14V9m-9 4h4",
  inbox: "M4 13h4l2 3h4l2-3h4M4 13l2-8h12l2 8v6H4v-6Z",
  block: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM5.6 5.6l12.8 12.8",
  flag: "M5 21V4m0 0h11l-2 4 2 4H5",
  sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0-15v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
  moon: "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z",
  monitor: "M3 5h18v11H3V5Zm6 15h6m-3-4v4",
} as const;

export type IconName = keyof typeof paths;

export function Icon({
  name,
  ...props
}: { name: IconName } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className="icon"
      {...props}
    >
      <path d={paths[name]} />
    </svg>
  );
}

/** The Poketto mark: a page resting in a pocket. */
export function BrandMark({
  className = "brand-mark",
}: {
  className?: string;
}) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <rect
        x="9.5"
        y="2.5"
        width="13"
        height="15"
        rx="2.5"
        transform="rotate(8 16 10)"
        fill="var(--accent)"
      />
      <path
        d="M4 12h24v8.5A8.5 8.5 0 0 1 19.5 29h-7A8.5 8.5 0 0 1 4 20.5V12Z"
        fill="currentColor"
      />
      <path
        d="M11 19.5c1.4 1.6 3 2.4 5 2.4s3.6-.8 5-2.4"
        fill="none"
        stroke="var(--bg)"
        strokeWidth="2.2"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** A stable hue for an account or space initial. */
export function hue(value: string) {
  let hash = 0;
  for (const character of value)
    hash = (hash * 31 + character.codePointAt(0)!) % 360;
  return hash;
}

export function Avatar({
  name,
  large = false,
}: {
  name: string;
  large?: boolean;
}) {
  const initial = [...(name.trim() || "?")][0].toUpperCase();
  return (
    <span
      className={large ? "avatar avatar-lg" : "avatar"}
      style={{ "--hue": hue(name) } as React.CSSProperties}
      aria-hidden
    >
      {initial}
    </span>
  );
}
