import type { Metadata } from "next";
import "./globals.css";
import { SiteBar, SiteFooter } from "../components/site-chrome";
import { GoogleReturnNotice } from "../components/google-login";

export const metadata: Metadata = {
  title: { default: "Poketto · 记录与收藏", template: "%s · Poketto" },
  description: "那些值得留下的想法、故事与发现。",
};
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
          {/* A failed Google sign-in returns here with loginError; the first reader shows it. */}
          <GoogleReturnNotice />
        </div>
        <main id="main">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
