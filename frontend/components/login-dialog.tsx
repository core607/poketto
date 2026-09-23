"use client";
import { useEffect, useRef, useState } from "react";
import { Login } from "./login";
import { Icon } from "./ui/icons";

/** Opens the sign-in card over the current page so a reader keeps their place. */
export function LoginDialog({
  label = "登录",
  className = "btn btn-primary btn-sm",
  onLogin,
}: {
  label?: string;
  className?: string;
  onLogin: () => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (open) dialog.current?.showModal();
    else dialog.current?.close();
  }, [open]);
  return (
    <>
      <button type="button" className={className} onClick={() => setOpen(true)}>
        {label}
      </button>
      <dialog
        ref={dialog}
        className="login-dialog"
        aria-label="登录 Poketto"
        onClose={() => setOpen(false)}
        onClick={(event) => {
          if (event.target === event.currentTarget) setOpen(false);
        }}
      >
        {open && (
          <>
            <button
              type="button"
              className="icon-btn login-dialog-close"
              aria-label="关闭"
              onClick={() => setOpen(false)}
            >
              <Icon name="x" />
            </button>
            <Login
              embedded
              onLogin={async () => {
                setOpen(false);
                await onLogin();
              }}
            />
          </>
        )}
      </dialog>
    </>
  );
}
