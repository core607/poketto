"use client";
import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

type Confirmation = {
  title: string;
  description: string;
  confirmLabel: string;
};
const ConfirmationContext = createContext<{
  confirm: (owner: string, request: Confirmation) => Promise<boolean>;
  cancel: (owner: string) => void;
} | null>(null);

export function ConfirmationProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<
    (Confirmation & { returnFocus: HTMLElement | null }) | null
  >(null);
  const resolve = useRef<{
    owner: string;
    done: (confirmed: boolean) => void;
  } | null>(null);
  const confirm = useCallback((owner: string, next: Confirmation) => {
    if (resolve.current) return Promise.resolve(false);
    return new Promise<boolean>((done) => {
      resolve.current = { owner, done };
      setRequest({
        ...next,
        returnFocus:
          document.activeElement instanceof HTMLElement
            ? document.activeElement
            : null,
      });
    });
  }, []);
  const settle = useCallback((confirmed: boolean) => {
    const pending = resolve.current;
    resolve.current = null;
    setRequest(null);
    pending?.done(confirmed);
  }, []);
  const cancel = useCallback(
    (owner: string) => {
      if (resolve.current?.owner === owner) settle(false);
    },
    [settle],
  );
  useEffect(
    () => () => {
      resolve.current?.done(false);
      resolve.current = null;
    },
    [],
  );
  return (
    <ConfirmationContext value={{ confirm, cancel }}>
      {children}
      {request && <ConfirmationDialog request={request} onDecision={settle} />}
    </ConfirmationContext>
  );
}

export function useConfirmation() {
  const context = useContext(ConfirmationContext);
  const owner = useId();
  if (!context) throw new Error("ConfirmationProvider is required");
  const { confirm, cancel } = context;
  useEffect(() => () => cancel(owner), [cancel, owner]);
  return useCallback(
    (request: Confirmation) => confirm(owner, request),
    [confirm, owner],
  );
}

function ConfirmationDialog({
  request,
  onDecision,
}: {
  request: Confirmation & { returnFocus: HTMLElement | null };
  onDecision: (confirmed: boolean) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useLayoutEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => {
      element.close();
      if (request.returnFocus?.isConnected) request.returnFocus.focus();
    };
  }, [request.returnFocus]);
  return (
    <dialog
      ref={dialog}
      className="confirmation-dialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        event.preventDefault();
        onDecision(false);
      }}
    >
      <h2 id={titleId}>{request.title}</h2>
      <p id={descriptionId}>{request.description}</p>
      <div className="confirmation-actions">
        <button
          type="button"
          className="button-secondary"
          autoFocus
          onClick={() => onDecision(false)}
        >
          取消
        </button>
        <button
          type="button"
          className="button-danger"
          onClick={() => onDecision(true)}
        >
          {request.confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
