import { useEffect, useRef } from "react";

export function useModalFocus<T extends HTMLElement>(busy: boolean, onClose: () => void, active = true) {
  const initialFocusRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<T>(null);
  const behavior = useRef({ busy, onClose });

  useEffect(() => {
    behavior.current = { busy, onClose };
  }, [busy, onClose]);
  useEffect(() => {
    if (!active) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousIdentity = focusIdentity(previous);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    initialFocusRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !behavior.current.busy) behavior.current.onClose();
      if (event.key === "Tab") trapFocus(event, dialogRef.current);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      setTimeout(() => restoreFocus(previous, previousIdentity), 0);
    };
  }, [active]);

  return { dialogRef, initialFocusRef };
}

function focusIdentity(element: HTMLElement | null) {
  if (element === null) return null;
  return {
    tagName: element.tagName,
    id: element.id,
    ariaLabel: element.getAttribute("aria-label") ?? "",
    text: element.textContent?.trim() ?? "",
  };
}

function restoreFocus(element: HTMLElement | null, identity: ReturnType<typeof focusIdentity>) {
  if (document.querySelector('[role="dialog"][aria-modal="true"]') !== null) return;
  if (element !== null && document.contains(element)) {
    element.focus();
    return;
  }
  if (identity === null) return;
  const replacement = Array.from(document.querySelectorAll<HTMLElement>(identity.tagName)).find((candidate) =>
    (identity.id !== "" && candidate.id === identity.id)
    || (identity.ariaLabel !== "" && candidate.getAttribute("aria-label") === identity.ariaLabel)
    || (identity.text !== "" && candidate.textContent?.trim() === identity.text));
  replacement?.focus();
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
