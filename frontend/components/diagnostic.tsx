import type { Diagnostic } from "../lib/types";

const descriptions = new Map([
  [
    "INFERRED_METADATA",
    "未填写的标题由正文或文件名补充，日期取自 Git 历史，无须补齐元数据。",
  ],
  [
    "INVALID_MARKDOWN",
    "文章元数据格式有误，请检查文件开头的字段。其他正常文章仍可阅读。",
  ],
  ["INVALID_UTF8", "文件不是有效的 UTF-8 文本，请检查编码或二进制内容。"],
  ["INVALID_PATH", "文件路径不符合要求，请检查路径名称。"],
  ["NOT_REGULAR_FILE", "此路径不是普通文件，无法作为文章读取。"],
  ["FILE_TOO_LARGE", "文件超出文本大小限制，请缩减内容或拆分文件。"],
  ["PATH_COLLISION", "路径在大小写或字符规范化后重名，请修改其中一个文件名。"],
  [
    "ROUTE_COLLISION",
    "多个文件使用了同一个文章地址，请调整文件名或路由元数据。",
  ],
  ["INVALID_IMAGE", "图片格式、尺寸或内容无法验证，请检查原图。"],
]);

export function DiagnosticMessage({ diagnostic }: { diagnostic: Diagnostic }) {
  return (
    <>
      <p>
        {descriptions.get(diagnostic.code) ??
          "文件需要检查，请展开技术详情查看原因。"}
      </p>
      {diagnostic.code !== "INFERRED_METADATA" && (
        <details>
          <summary>技术详情</summary>
          <p>
            {diagnostic.code}: {diagnostic.message}
          </p>
        </details>
      )}
    </>
  );
}
