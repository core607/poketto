export default function NotFound() {
  return (
    <section className="page-shell empty-state">
      <p className="eyebrow">404</p>
      <h1>这一页没有找到。</h1>
      <p>它可能已被移走，或者还没有公开。</p>
      <a className="button" href="/">
        回到文章
      </a>
    </section>
  );
}
