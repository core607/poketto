import { SiteConsole } from "../../components/site-console";

export const metadata = {
  title: "站点管理",
  robots: { index: false, follow: false },
};

export default function SitePage() {
  return <SiteConsole />;
}
