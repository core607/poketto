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
  return spaceListingMetadata((await params).space, "tags", await searchParams);
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
      view="tags"
      parameters={await searchParams}
    />
  );
}
