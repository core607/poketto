import { CommunityDashboard } from "../../components/community-dashboard";
export const metadata = {
  title: "我的社区",
  robots: { index: false, follow: false },
};
export default function CommunityPage() {
  return <CommunityDashboard />;
}
