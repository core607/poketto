"use client";
import { useState } from "react";
export function Secret({
  title,
  value,
  onClose,
}: {
  title: string;
  value: string;
  onClose: () => void;
}) {
  const [visible, setVisible] = useState(false);
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);
  return (
    <section className="secret-panel" role="status">
      <h3>{title}</h3>
      <p>请保存在可信的位置。关闭后无法再次查看。</p>
      <label className="sr-only" htmlFor="created-secret">
        新凭证
      </label>
      <input
        id="created-secret"
        type={visible ? "text" : "password"}
        value={value}
        readOnly
        autoComplete="off"
      />
      <div className="button-row">
        <button
          className="button-secondary"
          onClick={() => setVisible(!visible)}
        >
          {visible ? "隐藏" : "显示"}
        </button>
        <button
          className="button-secondary"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setFailed(false);
            } catch {
              setFailed(true);
            }
          }}
        >
          {copied ? "已复制" : "复制"}
        </button>
        <button className="text-button" onClick={onClose}>
          已保存，关闭
        </button>
      </div>
      {failed && <p>复制未成功，请点击“显示”后手动保存。</p>}
    </section>
  );
}
