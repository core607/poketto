import { permanentRedirect } from "next/navigation";
import { defaultSpace } from "../../lib/public-api";
import { spaceHref } from "../../lib/format";

export const metadata = { title: "归档" };
// The root archive belongs to the default space; its site owns the canonical view.
export default async function Archive({
  searchParams,
}: {
  searchParams: Promise<{ offset?: string | string[] }>;
}) {
  const { offset } = await searchParams;
  const space = await defaultSpace();
  permanentRedirect(
    spaceHref(space.slug) +
      "/archive" +
      (typeof offset === "string"
        ? "?offset=" + encodeURIComponent(offset)
        : ""),
  );
}
