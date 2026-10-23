export const siteGroups = {
  VIEWER: "浏览者",
  COMMUNITY: "社区成员",
  CREATOR: "创作者",
  ADMINISTRATOR: "站点管理员",
};
export type SiteGroup = keyof typeof siteGroups;

/** What each group may do on this site, in the words an account holder reads. */
export const siteGroupAbilities: Record<SiteGroup, string> = {
  VIEWER: "可以阅读公开内容、管理自己的账号，并加入别人邀请你的空间。",
  COMMUNITY: "在浏览者基础上，还可以收藏、点赞、关注空间和发表评论。",
  CREATOR: "在社区成员基础上，还可以新建自己的空间并公开成网站。",
  ADMINISTRATOR: "拥有创作者的全部能力，并负责站点账号和举报处理。",
};

/** Only these groups may create spaces and keep websites public. */
export function canCreateSpaces(group: SiteGroup) {
  return group === "CREATOR" || group === "ADMINISTRATOR";
}
