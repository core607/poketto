import type { Metadata } from "next";
import "./globals.css";
import { SiteBar, SiteFooter } from "../components/site-chrome";
import { GoogleReturnNotice } from "../components/google-login";
import { publicOrigin } from "../lib/public-api";

export function generateMetadata(): Metadata {
  // Relative alternates such as space feeds resolve against the public origin, not the dev server.
  let metadataBase: URL | undefined;
  try {
    metadataBase = new URL(publicOrigin());
  } catch {
    metadataBase = undefined;
  }
  return {
    title: { default: "Poketto · 记录与收藏", template: "%s · Poketto" },
    description: "那些值得留下的想法、故事与发现。",
    metadataBase,
  };
}
export const dynamic = "force-dynamic";
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <a href="#main" className="skip-link">
          跳转到正文
        </a>
        <SiteBar />
        <div className="global-notice">
          <GoogleReturnNotice global />
        </div>
        <main id="main">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
