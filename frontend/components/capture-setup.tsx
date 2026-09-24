"use client";
import { useEffect, useState } from "react";
import { useWorkspaceApi } from "./workspace-context";
import { message, type Identity } from "./admin";
import { Secret } from "./secret";

/**
 * Ways into the space's private inbox: an iPhone shortcut holding a capture-only key, and a
 * bookmarklet that opens the same-origin /capture popup with the browser session.
 */
export function CaptureSetup({ identity }: { identity: Identity }) {
  const api = useWorkspaceApi();
  const [origin, setOrigin] = useState("");
  const [secret, setSecret] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  useEffect(() => setOrigin(window.location.origin), []);
  const writer =
    identity.role === "OWNER" ||
    identity.capabilities.includes("WRITE_PRIVATE");
  if (!writer) return null;
  const bookmarklet =
    "javascript:(()=>{window.open('" +
    origin +
    "/capture?'+new URLSearchParams({title:document.title,url:location.href,text:String(getSelection())}),'poketto-capture','width=480,height=680')})()";

  async function issue() {
    setPending(true);
    setError("");
    try {
      const value = await api<{ token: string }>("/api/admin/keys", {
        method: "POST",
        body: { accountId: identity.accountId, capabilities: ["CAPTURE"] },
      });
      setSecret(value.token);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(bookmarklet);
      setCopied(true);
    } catch {
      setError("无法写入剪贴板，请手动选中代码复制。");
    }
  }

  return (
    <section className="sub-panel capture-setup" aria-label="收集入口">
      <h2>收集入口</h2>
      <p className="muted">
        在手机的分享菜单或电脑的书签栏里，一步把链接、摘录和照片存进这个空间的
        private/inbox/。它们不会公开，之后可以让 AI 助手帮你整理。
      </p>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {identity.role === "OWNER" && (
        <>
          <h3>iPhone 快捷指令</h3>
          <ol className="capture-steps">
            <li>
              打开「快捷指令」App
              新建一个快捷指令，在详情里开启「在共享表单中显示」，接收
              URL、文本和图像。
            </li>
            <li>添加「要求输入」，提示写「备注」，允许留空。</li>
            <li>
              添加「获取 URL 内容」：网址填 <code>{origin}/api/capture</code>
              ，方法选 POST；添加头部 <code>Authorization</code>，值为{" "}
              <code>Bearer </code>
              加上下面生成的密钥；请求体选「表单」，添加字段 <code>
                url
              </code> 和 <code>text</code>（都选快捷指令输入）、
              <code>note</code>
              （上一步的输入），分享照片时再加文件字段 <code>image</code>。
            </li>
            <li>添加「显示通知」，内容写「已存进口袋」。</li>
          </ol>
          <button type="button" disabled={pending} onClick={() => void issue()}>
            {pending ? "正在生成…" : "生成收集专用密钥"}
          </button>
          <p className="muted">
            这把密钥只能往收件箱里新建笔记和配图，不能读取或修改别的内容；在「访问密钥」里可以随时撤销。
          </p>
          {secret && (
            <Secret
              title="收集密钥只显示这一次"
              value={secret}
              onClose={() => setSecret("")}
            />
          )}
        </>
      )}
      <h3>电脑浏览器书签</h3>
      <p>
        新建一个书签，把网址设成下面这段代码。在任意网页上点它，会弹出一个小窗口，选中的文字会一起带过来，确认后保存。
      </p>
      <textarea
        className="capture-bookmarklet"
        aria-label="书签代码"
        readOnly
        rows={3}
        value={bookmarklet}
        onFocus={(event) => event.currentTarget.select()}
      />
      <button type="button" onClick={() => void copy()}>
        {copied ? "已复制" : "复制书签代码"}
      </button>
    </section>
  );
}
