import { CommunityDashboard } from "../../components/community-dashboard";
import { communityTabs } from "../../lib/community";
export const metadata = {
  title: "我的社区",
  robots: { index: false, follow: false },
};
export default async function CommunityPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const { tab } = await searchParams;
  return (
    <CommunityDashboard
      initialTab={
        communityTabs.find((item) => item === tab && item !== "reports") ??
        "feed"
      }
    />
  );
}
