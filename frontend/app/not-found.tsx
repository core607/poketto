export default function NotFound() {
  return (
    <section className="state-page">
      <p className="state-code">404</p>
      <h1>这一页没有找到。</h1>
      <p>它可能已被移走、改名，或者还没有公开。</p>
      <a className="btn btn-primary" href="/">
        回到发现
      </a>
    </section>
  );
}
