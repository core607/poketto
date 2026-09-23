import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";
import { SiteBar, SiteFooter } from "../components/site-chrome";
import { GoogleReturnNotice } from "../components/google-login";
import { publicOrigin } from "../lib/public-api";
import { themeScript } from "../lib/theme";
import { SHARE_IMAGE } from "../lib/format";

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
    // Pages that set their own openGraph replace this object, so they repeat the image.
    openGraph: {
      type: "website",
      siteName: "Poketto",
      images: [SHARE_IMAGE],
    },
    twitter: { card: "summary_large_image", images: [SHARE_IMAGE.url] },
  };
}
export const dynamic = "force-dynamic";
export default async function Layout({
  children,
}: {
  children: React.ReactNode;
}) {
  // The proxy issues a per-request nonce; the CSP admits only scripts carrying it.
  const nonce = (await headers()).get("x-nonce") ?? undefined;
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script
          nonce={nonce}
          dangerouslySetInnerHTML={{ __html: themeScript }}
        />
      </head>
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
