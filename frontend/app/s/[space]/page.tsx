import type { Metadata } from "next";
import {
  PublicSpacePage,
  type SpaceParameters,
} from "../../../components/public-space";
import { spaceInfo } from "../../../lib/public-api";
import { SHARE_IMAGE, spaceFeed, spaceHref } from "../../../lib/format";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ space: string }>;
}): Promise<Metadata> {
  const { space } = await params;
  const info = await spaceInfo(space).catch(() => null);
  const name = info?.displayName ?? space;
  const description = info?.description?.trim() || `「${name}」的公开记录`;
  return {
    description,
    alternates: { canonical: spaceHref(space), types: spaceFeed(space, name) },
    openGraph: {
      type: "website",
      siteName: "Poketto",
      title: name,
      description,
      url: spaceHref(space),
      images: [SHARE_IMAGE],
    },
  };
}

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ space: string }>;
  searchParams: Promise<SpaceParameters>;
}) {
  return (
    <PublicSpacePage
      slug={(await params).space}
      view="home"
      parameters={await searchParams}
    />
  );
}
