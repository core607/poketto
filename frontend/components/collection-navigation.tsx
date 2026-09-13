import { articleHref, collectionArticleHref } from "../lib/format";
import type { CollectionNavigation as Navigation } from "../lib/types";

export function CollectionNavigation({
  navigation,
  selected,
  route,
  space,
}: {
  navigation?: Navigation;
  selected?: string;
  route: string;
  space: string;
}) {
  if (!navigation) return null;
  const membership = navigation.memberships.find(
    (item) => item.collection.route === selected,
  );
  const href = (target: string, collection: string) =>
    collectionArticleHref(target, space, collection);
  return (
    <>
      {!navigation.available && (
        <p role="status" className="notice">
          合集目录暂时无法生成，仍可阅读下方原文。
        </p>
      )}
      {navigation.entries.length > 0 && (
        <details className="collection-directory">
          <summary>合集目录 · {navigation.entries.length} 篇</summary>
          <ol>
            {navigation.entries.map((entry) => (
              <li key={entry.route}>
                <a href={href(entry.route, route)}>{entry.title}</a>
              </li>
            ))}
          </ol>
        </details>
      )}
      {membership ? (
        <nav className="collection-reading" aria-label="合集阅读">
          <a href={articleHref(membership.collection.route, space)}>
            ← 返回合集：{membership.collection.title}
          </a>
          <p>
            第 {membership.position} / {membership.total} 篇
          </p>
          <div className="pagination">
            {membership.previous ? (
              <a
                href={href(
                  membership.previous.route,
                  membership.collection.route,
                )}
              >
                ← 上一篇：{membership.previous.title}
              </a>
            ) : (
              <span>这是第一篇</span>
            )}
            {membership.next ? (
              <a
                href={href(membership.next.route, membership.collection.route)}
              >
                下一篇：{membership.next.title} →
              </a>
            ) : (
              <span>已读到本合集最后一篇</span>
            )}
          </div>
        </nav>
      ) : (
        navigation.memberships.length > 0 && (
          <nav className="collection-reading" aria-label="选择所属合集">
            <p>从合集继续阅读</p>
            <ul>
              {navigation.memberships.map((item) => (
                <li key={item.collection.route}>
                  <a href={href(route, item.collection.route)}>
                    {item.collection.title} · 第 {item.position} / {item.total}{" "}
                    篇
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        )
      )}
    </>
  );
}
