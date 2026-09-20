import type { ReactNode } from "react";

export function PolicyPage({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  const email = process.env.POKETTO_SUPPORT_EMAIL?.trim() ?? "";
  const contact =
    email.length <= 254 && /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(email)
      ? email
      : "";
  return (
    <article className="page-shell narrow policy-page">
      <header className="page-heading">
        <h1>{title}</h1>
      </header>
      {children}
      <section>
        <h2>联系我们</h2>
        <p>
          有关账号、内容处理或本页面的问题，请联系本站维护者。
          {contact && (
            <>
              邮箱：<a href={`mailto:${contact}`}>{contact}</a>。
            </>
          )}
        </p>
        <p>
          提出账号或数据处理请求时，请说明相关账号和内容；处理前可能需要核实你与该账号的关系。请勿发送密码、验证码或访问密钥。
        </p>
      </section>
      <p>
        <a href="/privacy">隐私政策</a> · <a href="/terms">服务条款</a> ·{" "}
        <a href="/admin">账号管理</a>
      </p>
    </article>
  );
}
