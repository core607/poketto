import { articles } from "../../lib/public-api";
import { rss } from "../../lib/rss";
export const dynamic = "force-dynamic";
export async function GET() {
  return rss(async () => ({
    title: "Poketto · 记录与收藏",
    link: "",
    description: "那些值得留下的想法、故事与发现。",
    items: (await articles({ limit: "30" })).items,
  }));
}
