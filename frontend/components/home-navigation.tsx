import { Icon } from "./ui/icons";

export function HomeNavigation({ following = false }: { following?: boolean }) {
  return (
    <nav className="segmented" aria-label="首页栏目">
      <a href="/" aria-current={!following ? "page" : undefined}>
        <Icon name="compass" />
        发现
      </a>
      <a href="/?view=following" aria-current={following ? "page" : undefined}>
        <Icon name="users" />
        关注
      </a>
      <a href="/community?tab=bookmarks">
        <Icon name="bookmark" />
        收藏
      </a>
    </nav>
  );
}
