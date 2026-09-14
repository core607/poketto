import { ApiError } from "./browser-api";
import { inContentRoot } from "./repository-directory";

export type ContentLocation = { folder: string; path: string };

export function parentDirectory(path: string) {
  return path.slice(0, Math.max(0, path.lastIndexOf("/")));
}

export function navigationFolder(value: string) {
  const folder = value.replace(/\/+$/, "");
  return folder.length <= 255 &&
    !/^[\/]|[\\\u0000-\u001f\u007f]/.test(folder) &&
    (!folder ||
      folder.split("/").every((part) => part && part !== "." && part !== ".."))
    ? folder
    : "";
}

export function privateCreationPath(
  folder: string,
  name: string,
  kind: "note" | "folder",
) {
  const component = name.trim();
  if (
    !component ||
    component === "." ||
    component === ".." ||
    /[\/\\\u0000-\u001f\u007f]/.test(component)
  )
    throw new ApiError(400, "请输入一个名称，不要包含路径分隔符或控制字符。");
  const target =
    inContentRoot(navigationFolder(folder), "private") +
    "/" +
    component +
    (kind === "folder" ? "/index.md" : /\.md$/i.test(component) ? "" : ".md");
  if (target.length > 255)
    throw new ApiError(400, "路径过长，请缩短名称或选择更靠上的目录。");
  return target;
}
