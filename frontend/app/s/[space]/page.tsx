import {
  PublicSpacePage,
  type SpaceParameters,
} from "../../../components/public-space";

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
