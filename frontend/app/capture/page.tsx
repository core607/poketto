import type { Metadata } from "next";
import { CaptureForm } from "../../components/capture-form";

export const metadata: Metadata = {
  title: "存进口袋",
  robots: { index: false, follow: false },
};

type Parameters = {
  title?: string | string[];
  url?: string | string[];
  text?: string | string[];
};

function first(value: string | string[] | undefined, limit: number) {
  return (typeof value === "string" ? value : "").slice(0, limit);
}

export default async function Capture({
  searchParams,
}: {
  searchParams: Promise<Parameters>;
}) {
  const parameters = await searchParams;
  return (
    <div className="page capture-page">
      <h1>存进口袋</h1>
      <CaptureForm
        title={first(parameters.title, 200)}
        url={first(parameters.url, 2048)}
        text={first(parameters.text, 20000)}
      />
    </div>
  );
}
