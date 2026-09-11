import { Connect } from "../../components/connect";
export const metadata = {
  title: "连接授权",
  robots: { index: false, follow: false },
  referrer: "no-referrer" as const,
};
export default function ConnectPage() {
  return <Connect />;
}
