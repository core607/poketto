"use client";

export default function SearchError({ reset }: { reset: () => void }) {
  return (
    <section className="page-shell empty-state" role="alert">
      <h1>站点搜索暂时不可用。</h1>
      <p>
        部分公开空间尚未准备好，或本次查询超出处理上限。请稍后重试，也可以进入某个空间单独搜索。
      </p>
      <button onClick={reset}>重试搜索</button>
      <a href="/">返回首页</a>
    </section>
  );
}
