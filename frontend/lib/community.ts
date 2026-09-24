import { ApiError } from "./browser-api";

export const communityRoot = "/api/auth/community";
export const publicCommunityRoot = "/api/public/community";
export const communityTabs = [
  "feed",
  "bookmarks",
  "likes",
  "notifications",
  "following",
  "blocks",
  "reports",
] as const;
export type CommunityTab = (typeof communityTabs)[number];
export type CommunityProfile = { accountId: string; displayName: string };
export type CommunityPage<T> = { items: T[]; nextBefore: number | null };
export type CommunityComment = {
  id: string;
  position: number;
  parentId: string | null;
  author: CommunityProfile | null;
  body: string;
  createdAt: string;
  deleted: boolean;
  replies: number;
  mayDelete: boolean;
};
export type SpaceParticipation = {
  following: boolean;
  accountId: string | null;
  mayParticipate: boolean;
};
export type ArticleThread = SpaceParticipation & {
  likes: number;
  liked: boolean;
  bookmarked: boolean;
  mayModerate: boolean;
  comments: CommunityPage<CommunityComment>;
};
export type CommunityArticle = {
  space: string;
  spaceName: string;
  articleId: string | null;
  route: string;
  title: string;
  authorName: string;
  createdAt: string;
};
export type SavedArticle = {
  position: number;
  article: CommunityArticle | null;
};
export type FollowedSpace = {
  position: number;
  space: string | null;
  displayName: string | null;
  available: boolean;
};
/** A comment, or a correction event with commentId null; older servers omit the correction fields. */
export type CommunityNotification = {
  position: number;
  commentId: string | null;
  actor: CommunityProfile | null;
  excerpt: string;
  article: CommunityArticle;
  createdAt: string;
  read: boolean;
  correctionId?: string | null;
  event?: "PROPOSED" | "ACCEPTED" | "DECLINED" | "STALE" | null;
};
/** What a notification says happened, after the actor's name. */
export function noticeAction(item: CommunityNotification) {
  switch (item.event) {
    case "PROPOSED":
      return `对《${item.article.title}》提了修改建议`;
    case "ACCEPTED":
      return `采纳了你对《${item.article.title}》的修改建议`;
    case "DECLINED":
      return `没有采纳你对《${item.article.title}》的修改建议`;
    case "STALE":
      return `处理时发现《${item.article.title}》已经改过，你的建议已过期`;
    default:
      return `回复了《${item.article.title}》`;
  }
}
export type CommunityReport = {
  position: number;
  commentId: string;
  reporter: CommunityProfile | null;
  author: CommunityProfile | null;
  reason: string;
  commentBody: string;
  createdAt: string;
  status: string;
};
export type BlockedAccount = {
  position: number;
  account: CommunityProfile | null;
};
export function communityMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "操作未能完成，请稍后重试。";
  const messages: Record<string, string> = {
    COMMUNITY_PARTICIPATION_REQUIRED:
      "社区成员及以上可新增互动。你仍可管理已有记录。",
    COMMUNITY_LIMIT_REACHED:
      "已达到操作频率或保存数量限制，请稍后重试，或先整理已有记录。",
    COMMUNITY_REQUEST_CONFLICT:
      "这次提交的内容与原请求不同，请刷新后核对评论。草稿已保留。",
    COMMUNITY_REPLY_UNAVAILABLE:
      "这条评论目前无法回复，请刷新后再试。草稿已保留。",
    COMMUNITY_UNAVAILABLE: "这篇文章或评论目前不可用。",
  };
  return messages[error.code ?? ""] ?? error.message;
}
