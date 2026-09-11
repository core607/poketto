import { type api as browserApi, ApiError } from "./browser-api";
import type { PatchResult, RepositoryFile } from "./types";

export async function saveRepositoryFile(
  api: typeof browserApi,
  file: RepositoryFile,
  target: string,
  source: string,
  remove: boolean,
): Promise<PatchResult> {
  const moved = target !== file.path;
  if (moved && !file.expectedAbsence)
    throw new ApiError(
      400,
      "已保存的文件请使用移动入口，以便同时更新相关链接。",
    );
  const changes = [];
  if (!file.expectedAbsence || !moved) {
    changes.push({
      path: file.path,
      expectedAbsence: file.expectedAbsence,
      expectedRevision: file.revision,
      content: remove || moved ? null : source,
    });
  }
  if (moved) {
    const destination = await api<RepositoryFile>(
      "/api/admin/repository/file?" + new URLSearchParams({ path: target }),
    );
    if (!destination.expectedAbsence)
      throw new ApiError(409, "目标路径已经存在，请换一个名字。");
    changes.push({
      path: target,
      expectedAbsence: true,
      expectedRevision: null,
      content: source,
    });
  }
  return api<PatchResult>("/api/admin/repository/patch", {
    method: "POST",
    body: { baseCommit: file.commit, changes },
  });
}
