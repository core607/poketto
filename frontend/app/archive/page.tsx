import { redirect } from "next/navigation";
import { defaultSpace } from "../../lib/public-api";
import { spaceHref } from "../../lib/format";

export const metadata = { title: "归档" };
// The root archive belongs to the default space. The target follows the current
// default space, so the redirect stays temporary.
export default async function Archive({
  searchParams,
}: {
  searchParams: Promise<{ offset?: string | string[] }>;
}) {
  const { offset } = await searchParams;
  const space = await defaultSpace();
  redirect(
    spaceHref(space.slug) +
      "/archive" +
      (typeof offset === "string"
        ? "?offset=" + encodeURIComponent(offset)
        : ""),
  );
}
