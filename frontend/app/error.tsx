"use client";
export default function ErrorPage() {
  return (
    <section className="state-page">
      <p className="state-code">···</p>
      <h1>内容暂时无法读取。</h1>
      <p>服务可能正在同步或重启，稍等片刻再试一次。</p>
      <button
        className="btn btn-primary"
        onClick={() => window.location.reload()}
      >
        重新加载
      </button>
    </section>
  );
}
