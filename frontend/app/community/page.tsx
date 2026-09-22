import { CommunityDashboard } from "../../components/community-dashboard";
export const metadata = {
  title: "我的社区",
  robots: { index: false, follow: false },
};
export default async function CommunityPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const parameters = await searchParams;
  return (
    <CommunityDashboard
      initialTab={parameters.tab === "bookmarks" ? "bookmarks" : "feed"}
    />
  );
}
