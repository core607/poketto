import { ApiError } from "./browser-api";

export const communityRoot = "/api/auth/community";
export const publicCommunityRoot = "/api/public/community";
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
export type CommunityNotification = {
  position: number;
  commentId: string;
  actor: CommunityProfile | null;
  excerpt: string;
  article: CommunityArticle;
  createdAt: string;
  read: boolean;
};
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
