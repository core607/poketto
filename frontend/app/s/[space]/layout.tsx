import type { Metadata } from "next";
import { spaceInfo } from "../../../lib/public-api";
import { spaceFeed } from "../../../lib/format";

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
    alternates: { types: spaceFeed(space, name) },
  };
}

export default function SpaceLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return children;
}
