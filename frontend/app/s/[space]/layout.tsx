import type { Metadata } from "next";
import { spaceInfo } from "../../../lib/public-api";
import { spaceHref } from "../../../lib/format";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ space: string }>;
}): Promise<Metadata> {
  const { space } = await params;
  const info = await spaceInfo(space).catch(() => null);
  const name = info?.displayName ?? space;
  return {
    title: { default: name, template: `%s · ${name} · Poketto` },
    alternates: {
      types: {
        "application/rss+xml": [
          { url: spaceHref(space) + "/rss.xml", title: name },
        ],
      },
    },
  };
}

export default function SpaceLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return children;
}
