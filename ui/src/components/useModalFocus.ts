import { useEffect, useRef } from "react";

export function useModalFocus<T extends HTMLElement>(busy: boolean, onClose: () => void) {
  const initialFocusRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<T>(null);
  const behavior = useRef({ busy, onClose });

  useEffect(() => {
    behavior.current = { busy, onClose };
  }, [busy, onClose]);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    initialFocusRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !behavior.current.busy) behavior.current.onClose();
      if (event.key === "Tab") trapFocus(event, dialogRef.current);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      previous?.focus();
    };
  }, []);

  return { dialogRef, initialFocusRef };
}

function trapFocus(event: KeyboardEvent, dialog: HTMLElement | null) {
  if (dialog === null) return;
  const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  ));
  const first = focusable.at(0);
  const last = focusable.at(-1);
  if (first === undefined) return;
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  } else if (!dialog.contains(document.activeElement)) {
    event.preventDefault();
    first.focus();
  }
}
