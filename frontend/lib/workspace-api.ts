import { api, ApiError } from "./browser-api";

export function workspacePath(workspaceId: string, path: string) {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      workspaceId,
    )
  )
    throw new ApiError(400, "请选择一个有效的空间。");
  if (!path.startsWith("/api/admin/")) return path;
  return (
    "/api/admin/workspaces/" + workspaceId + path.slice("/api/admin".length)
  );
}

export function workspaceApi(workspaceId: string): typeof api {
  return (path, options) => api(workspacePath(workspaceId, path), options);
}
