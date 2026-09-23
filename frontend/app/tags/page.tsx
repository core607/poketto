import { redirect } from "next/navigation";
import { defaultSpace } from "../../lib/public-api";
import { spaceHref } from "../../lib/format";

export const metadata = { title: "标签" };
// Root tag pages belong to the default space. The target follows the current
// default space, so the redirect stays temporary.
export default async function Tags({
  searchParams,
}: {
  searchParams: Promise<{
    tag?: string | string[];
    offset?: string | string[];
    tagOffset?: string | string[];
  }>;
}) {
  const { tag, offset, tagOffset } = await searchParams;
  const space = await defaultSpace();
  const query = new URLSearchParams();
  if (typeof tag === "string" && tag) {
    query.set("tag", tag);
    if (typeof offset === "string") query.set("offset", offset);
  } else if (typeof tagOffset === "string") query.set("offset", tagOffset);
  const suffix = query.size ? "?" + query : "";
  redirect(spaceHref(space.slug) + "/tags" + suffix);
}
