import type { NextConfig } from "next";

const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  images: { unoptimized: true },
  async rewrites() {
    if (process.env.NODE_ENV !== "development") return [];
    const backend = process.env.POKETTO_API_BASE_URL ?? "http://127.0.0.1:8080";
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};
export default config;
