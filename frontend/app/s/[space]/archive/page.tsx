import {
  PublicSpacePage,
  type SpaceParameters,
} from "../../../../components/public-space";

export const metadata = { title: "归档" };

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
