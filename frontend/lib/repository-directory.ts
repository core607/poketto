import { api, ApiError } from "./browser-api";
import type { RepositoryDirectory } from "./types";

export async function readDirectory(
  commit: string | null,
  path: string,
  offset = 0,
) {
  const query = new URLSearchParams({
    path,
    offset: String(offset),
    limit: "100",
  });
  if (commit) query.set("commit", commit);
  const result = await api<RepositoryDirectory>(
    "/api/admin/repository/directory?" + query,
  );
  if (commit && result.commit !== commit)
    throw new ApiError(409, "目录版本已改变，请刷新后重试。");
  return result;
}

export function movablePath(path: string) {
  return (
    path.toLowerCase() !== "public" &&
    path.toLowerCase() !== "private" &&
    !path.split("/").some((part) => part.toLowerCase() === ".poketto")
  );
}
