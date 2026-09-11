"use client";
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { api, ApiError } from "../lib/browser-api";
import { workspaceApi, workspacePath } from "../lib/workspace-api";

const WorkspaceContext = createContext<string | null>(null);

export function WorkspaceProvider({ workspaceId, children }: { workspaceId: string; children: ReactNode }) {
  return <WorkspaceContext.Provider value={workspaceId}>{children}</WorkspaceContext.Provider>;
}

const accountApi: typeof api = (path, options) => {
  if (path.startsWith("/api/admin/")) throw new ApiError(400, "请先选择空间。");
  return api(path, options);
};

export function useWorkspaceApi() {
  const workspace = useContext(WorkspaceContext);
  return useMemo(() => workspace ? workspaceApi(workspace) : accountApi, [workspace]);
}

export function useWorkspacePath() {
  const workspace = useContext(WorkspaceContext);
  return (path: string) => {
    if (!workspace) throw new ApiError(400, "请先选择空间。");
    return workspacePath(workspace, path);
  };
}
