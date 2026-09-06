import type { Metadata } from "next";
import "./globals.css";

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
        <header className="site-header">
          <a href="/" className="brand">
            <span className="brand-mark" aria-hidden>
              ▱
            </span>
            Poketto<span className="brand-note">记录与收藏</span>
          </a>
          <nav aria-label="主导航">
            <a href="/">文章</a>
            <a href="/tags">标签</a>
            <a href="/archive">归档</a>
            <a href="/search">搜索</a>
            <a href="/admin" className="nav-admin">
              管理 ↗
            </a>
          </nav>
        </header>
        <main id="main">{children}</main>
        <footer className="site-footer">
          <span>
            Poketto<span className="footer-dot">·</span>给想法一个留下来的地方。
          </span>
          <a href="/rss.xml">RSS 订阅 ↗</a>
        </footer>
      </body>
    </html>
  );
}
