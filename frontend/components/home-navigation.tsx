export function HomeNavigation({ following = false }: { following?: boolean }) {
  return (
    <nav className="community-tabs" aria-label="首页栏目">
      <a href="/" aria-current={!following ? "page" : undefined}>
        发现
      </a>
      <a href="/?view=following" aria-current={following ? "page" : undefined}>
        关注动态
      </a>
      <a href="/community?tab=bookmarks">私密收藏 ↗</a>
    </nav>
  );
}
