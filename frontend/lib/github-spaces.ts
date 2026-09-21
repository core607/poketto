export const githubRoot = "/api/auth/workspaces/github";

export type GitHubStatus = {
  available: boolean;
  eligibleToCreate: boolean;
  state:
    | "DISABLED"
    | "NOT_CONNECTED"
    | "CONNECTED"
    | "REFRESHING"
    | "REAUTHORIZATION"
    | "DISCONNECTED";
  githubUserId: number | null;
  login: string | null;
  version: number;
};
export type GitHubRequest = {
  requestId: string;
  displayName: string;
  slug: string;
  githubOwnerId: number;
  repositoryName: string;
};
export type GitHubResult = {
  requestId: string;
  workspaceId: string;
  stage:
    | "PREPARING"
    | "CREATING"
    | "UNCERTAIN"
    | "AWAITING_INSTALLATION"
    | "VALIDATING_INSTALLATION"
    | "INITIALIZING"
    | "READY"
    | "DISCONNECTED"
    | "BLOCKED"
    | "REJECTED";
  repositoryId: number | null;
  repository: string | null;
  workspaceCreated: boolean;
  initializationCommit: string | null;
  failureCode: string | null;
  retryAfterSeconds: number;
};
export type GitHubEntry = { request: GitHubRequest; result: GitHubResult };
export type GitHubHistory = { items: GitHubEntry[]; nextOffset: number | null };

export const githubStages: Record<GitHubResult["stage"], string> = {
  PREPARING: "准备创建仓库",
  CREATING: "正在创建私有仓库",
  UNCERTAIN: "正在确认仓库是否已创建，请保留这次申请",
  AWAITING_INSTALLATION: "仓库已创建，等待确认访问权限",
  VALIDATING_INSTALLATION: "正在核对仓库访问权限",
  INITIALIZING: "正在准备空间内容",
  READY: "空间已准备好，公开网站默认关闭",
  DISCONNECTED: "GitHub 授权已失效，请重新授权后继续",
  BLOCKED: "当前策略组不允许继续创建新空间，已有仓库保留",
  REJECTED: "GitHub 未接受这次建仓申请，请检查仓库名是否已使用",
};

export const githubFailures: Record<string, string> = {
  AUTHORIZATION_REQUIRED: "请重新授权 GitHub 后继续。",
  AUTHORIZATION_CHANGED: "GitHub 授权已变化，请重新读取状态后继续。",
  IDENTITY_CHANGED:
    "请授权原来的 GitHub 个人账号，不能用其他账号接替这次申请。",
  REPOSITORY_CHANGED: "仓库身份、所有者或私有状态已改变，请到 GitHub 核对。",
  INSTALLATION_REQUIRED:
    "请在 GitHub 的仓库授权范围中选中这个仓库，保存后回来继续。",
  CREATION_UNCERTAIN:
    "尚未确认建仓结果。继续查询这次申请，不要换一个申请重复建仓。",
  CREATION_REJECTED: "GitHub 未接受建仓请求，请检查仓库名是否已使用。",
  DUPLICATE: "空间地址或仓库已被使用，请检查已有空间；已创建的仓库会保留。",
  BUSY: "正在处理其他请求，请稍后查询结果。",
  UNAVAILABLE: "暂时无法完成 GitHub 操作，请保留这次申请并稍后继续。",
};

export function restoreGitHubRequest(raw: string | null): GitHubRequest | null {
  if (!raw) return null;
  try {
    const value: GitHubRequest = JSON.parse(raw);
    if (
      typeof value.requestId !== "string" ||
      !/^[0-9a-f-]{36}$/.test(value.requestId) ||
      typeof value.displayName !== "string" ||
      value.displayName.length > 120 ||
      typeof value.slug !== "string" ||
      !/^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(value.slug) ||
      !Number.isSafeInteger(value.githubOwnerId) ||
      value.githubOwnerId <= 0 ||
      typeof value.repositoryName !== "string" ||
      !/^[A-Za-z0-9_.-]{1,100}$/.test(value.repositoryName)
    )
      return null;
    return {
      requestId: value.requestId,
      displayName: value.displayName,
      slug: value.slug,
      githubOwnerId: value.githubOwnerId,
      repositoryName: value.repositoryName,
    };
  } catch {
    return null;
  }
}
