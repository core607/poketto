import { articleHref, collectionArticleHref } from "../lib/format";
import type { CollectionNavigation as Navigation } from "../lib/types";
import { Icon } from "./ui/icons";

type Membership = Navigation["memberships"][number];

export function selectedMembership(
  navigation: Navigation | undefined,
  selected: string | undefined,
): Membership | undefined {
  return navigation?.memberships.find(
    (item) => item.collection.route === selected,
  );
}

export function hasCollectionPanel(navigation: Navigation | undefined) {
  return Boolean(
    navigation &&
    (!navigation.available ||
      navigation.entries.length > 0 ||
      navigation.memberships.length > 0),
  );
}

/** The collection a page opens, belongs to, or is being read through. */
export function CollectionPanel({
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
  if (!navigation || !hasCollectionPanel(navigation)) return null;
  const membership = selectedMembership(navigation, selected);
  const href = (target: string, collection: string) =>
    collectionArticleHref(target, space, collection);
  return (
    <div className="collection-stack">
      {!navigation.available && (
        <p role="status" className="notice">
          合集目录暂时无法生成，仍可阅读原文。
        </p>
      )}
      {navigation.entries.length > 0 && (
        <nav className="collection-card" aria-label="合集目录">
          <header>
            <span>本合集</span>
            <strong>{navigation.entries.length} 篇</strong>
          </header>
          <ol>
            {navigation.entries.map((entry) => (
              <li key={entry.route}>
                <a href={href(entry.route, route)}>{entry.title}</a>
              </li>
            ))}
          </ol>
        </nav>
      )}
      {membership ? (
        <nav className="collection-card" aria-label="合集阅读">
          <header>
            <span>
              合集 · 第 {membership.position} / {membership.total} 篇
            </span>
            <a href={articleHref(membership.collection.route, space)}>
              {membership.collection.title}
            </a>
          </header>
          <div className="collection-choices">
            {membership.previous && (
              <a
                href={href(
                  membership.previous.route,
                  membership.collection.route,
                )}
              >
                ← {membership.previous.title}
              </a>
            )}
            {membership.next && (
              <a
                href={href(membership.next.route, membership.collection.route)}
              >
                {membership.next.title} →
              </a>
            )}
          </div>
        </nav>
      ) : (
        navigation.memberships.length > 0 && (
          <nav className="collection-card" aria-label="选择所属合集">
            <header>
              <span>收录于</span>
              <strong>从合集继续阅读</strong>
            </header>
            <div className="collection-choices">
              {navigation.memberships.map((item) => (
                <a
                  key={item.collection.route}
                  href={href(route, item.collection.route)}
                >
                  {item.collection.title} · 第 {item.position} / {item.total} 篇
                </a>
              ))}
            </div>
          </nav>
        )
      )}
    </div>
  );
}

/** Previous and next pages at the end of an article read through a collection. */
export function SequenceNavigation({
  membership,
  space,
}: {
  membership?: Membership;
  space: string;
}) {
  if (!membership) return null;
  const collection = membership.collection.route;
  return (
    <nav className="seq-nav" aria-label="合集翻页">
      {membership.previous ? (
        <a
          className="seq-link"
          href={collectionArticleHref(
            membership.previous.route,
            space,
            collection,
          )}
        >
          <small>上一篇</small>
          <strong>{membership.previous.title}</strong>
        </a>
      ) : (
        <span className="seq-end">这是合集的第一篇</span>
      )}
      {membership.next ? (
        <a
          className="seq-link next"
          href={collectionArticleHref(membership.next.route, space, collection)}
        >
          <small>下一篇</small>
          <strong>{membership.next.title}</strong>
        </a>
      ) : (
        <a className="seq-end" href={articleHref(collection, space)}>
          <span>
            <Icon name="check" /> 已读完「{membership.collection.title}」
          </span>
        </a>
      )}
    </nav>
  );
}
