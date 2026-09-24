import {
  PublicSpacePage,
  spaceListingMetadata,
  type SpaceParameters,
} from "../../../../components/public-space";

export async function generateMetadata({
  params,
  searchParams,
}: {
  params: Promise<{ space: string }>;
  searchParams: Promise<SpaceParameters>;
}) {
  return spaceListingMetadata(
    (await params).space,
    "archive",
    await searchParams,
  );
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
      view="archive"
      parameters={await searchParams}
    />
  );
}
