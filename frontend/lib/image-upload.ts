export type ImageInsertion = { start: number; end: number; source: string };
export type ImageUpload = {
  file: File;
  operation: string;
  automatic: boolean;
  insertion?: ImageInsertion;
};

export function imageUpload(
  files: File[],
  automatic = false,
  insertion?: ImageInsertion,
): ImageUpload {
  if (files.length !== 1)
    throw new Error("每次处理一张图片，请分开粘贴或拖入。");
  const file = files[0];
  if (
    file.type &&
    !["image/png", "image/jpeg", "image/webp", "image/gif"].includes(file.type)
  )
    throw new Error("请选择 PNG、JPEG、WebP 或 GIF 图片。");
  if (file.size > 16 * 1024 * 1024) throw new Error("图片不能超过 16 MiB。");
  return { file, operation: crypto.randomUUID(), automatic, insertion };
}
