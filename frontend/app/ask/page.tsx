import type { Metadata } from "next";
import { QaPanel } from "../../components/qa-panel";

export const metadata: Metadata = {
  title: "许愿井 · 站内问答",
  robots: { index: false, follow: false },
};

export default function Ask() {
  return (
    <div className="page">
      <header className="page-header">
        <h1>许愿井</h1>
        <p>带着一个问题，沿着出处找下去。</p>
      </header>
      <QaPanel />
    </div>
  );
}
