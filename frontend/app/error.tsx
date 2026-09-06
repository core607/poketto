"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <section className="page-shell empty-state">
      <span aria-hidden>☁</span>
      <h1>内容暂时无法读取。</h1>
      <p>稍等片刻，再回来看看。</p>
      <button onClick={reset}>重新加载</button>
    </section>
  );
}
