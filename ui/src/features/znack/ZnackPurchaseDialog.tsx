import { RefreshCw, ShieldCheck, ShoppingCart, X } from "lucide-react";
import { useState } from "react";
import { useModalFocus } from "../../components/useModalFocus";
import { commands } from "../../generated/commands";
import type { ProductItem, PurchasePreview } from "../../generated/types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const VERSION = /^[0-9a-f]{64}$/;
const PREVIEW_WARNINGS = new Set(["automatic_introduction"]);

export function ZnackPurchaseDialog({
  shopId,
  product,
  settingsVersion,
  canPurchase,
  onClose,
  onStarted,
}: {
  shopId: number;
  product: Pick<ProductItem, "gtin" | "productName">;
  settingsVersion: string;
  canPurchase: boolean;
  onClose: () => void;
  onStarted: () => void;
}) {
  const [quantity, setQuantity] = useState("1");
  const [preview, setPreview] = useState<PurchasePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { dialogRef, initialFocusRef } = useModalFocus<HTMLDivElement>(busy, onClose);

  const prepare = async () => {
    if (busy || !canPurchase || !VERSION.test(settingsVersion)) return;
    const parsedQuantity = Number(quantity);
    if (!Number.isInteger(parsedQuantity) || parsedQuantity <= 0 || parsedQuantity > 10_000) {
      setError("Укажите целое количество от 1 до 10 000.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const response = await commands.znack.preparePurchase({
        shopId,
        gtin: product.gtin,
        quantity: parsedQuantity,
        version: settingsVersion,
      });
      if (!validPreview(response, shopId, product.gtin, parsedQuantity, settingsVersion)) {
        throw new Error("invalid preview");
      }
      setQuantity(String(parsedQuantity));
      setPreview(response);
    } catch {
      setError("Не удалось подготовить покупку. Обновите данные и повторите.");
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    if (preview === null || busy || !canPurchase || !VERSION.test(settingsVersion)) return;
    setBusy(true);
    setError("");
    try {
      const response = await commands.znack.startPurchase({
        shopId,
        purchaseId: preview.purchaseId,
        version: settingsVersion,
        confirmed: true,
      });
      if (!response.accepted || response.purchase.purchaseId !== preview.purchaseId
        || response.purchase.gtin !== preview.gtin || response.purchase.quantity !== preview.quantity) {
        throw new Error("invalid receipt");
      }
      onStarted();
    } catch {
      setError("Покупка не запущена. Проверьте состояние заказа перед повтором.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/45 p-4" role="dialog" aria-modal="true" aria-labelledby="purchase-dialog-title">
      <div ref={dialogRef} className="w-full max-w-lg rounded-2xl border border-[var(--border-subtle)] bg-[var(--surface-elevated)] p-5 shadow-2xl">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h4 id="purchase-dialog-title" className="text-lg font-semibold">{preview ? "Подтверждение покупки КИЗ" : "Подготовить покупку КИЗ"}</h4>
            <p className="mt-1 text-sm text-[var(--text-muted)]">{product.productName || "Товар без названия"}</p>
          </div>
          <button ref={initialFocusRef} className="icon-button" type="button" aria-label="Закрыть покупку КИЗ" disabled={busy} onClick={onClose}><X aria-hidden="true" size={18} /></button>
        </div>
        <div className="mt-4 rounded-xl border border-[var(--border-subtle)] bg-[var(--surface-muted)] p-4">
          <div className="flex items-center justify-between gap-3"><span className="text-xs text-[var(--text-muted)]">GTIN</span><code className="text-xs font-semibold">{product.gtin}</code></div>
          {preview ? (
            <>
              <div className="mt-3 flex items-center justify-between gap-3"><span className="text-xs text-[var(--text-muted)]">Количество</span><strong className="text-sm">{preview.quantity}</strong></div>
              {preview.autoIntroduction ? <p className="mt-4 flex items-start gap-2 rounded-lg bg-[var(--warning-soft)] p-3 text-xs leading-5 text-[var(--text-secondary)]"><ShieldCheck aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]" size={14} /><span><strong className="block text-[var(--text-primary)]">Автоматический ввод в оборот включён</strong>После загрузки кодов WCode отправит документ только при готовых данных и документах.</span></p> : <p className="mt-4 text-xs leading-5 text-[var(--text-muted)]">Коды будут загружены локально без автоматического ввода в оборот.</p>}
            </>
          ) : (
            <label className="field-label mt-4">
              <span>Количество КИЗ</span>
              <input type="number" min={1} max={10_000} step={1} className="text-input" value={quantity} disabled={busy} onChange={(event) => { setQuantity(event.target.value); setError(""); }} />
            </label>
          )}
        </div>
        {error ? <p className="mt-3 text-sm text-[var(--danger)]" role="alert">{error}</p> : null}
        {!canPurchase ? <p className="mt-3 text-xs leading-5 text-[var(--warning)]">Сохраните настройки и проверьте сертификат CryptoPro.</p> : null}
        <p className="mt-4 text-xs leading-5 text-[var(--text-muted)]">Покупка может создать платный заказ Znack. UUID подтверждения сохраняется до первого сетевого вызова и блокирует повторное списание.</p>
        <div className="mt-5 flex justify-end gap-2">
          <button className="secondary-button" type="button" disabled={busy} onClick={onClose}>Отмена</button>
          {preview ? (
            <button className="primary-button" type="button" aria-label="Подтвердить покупку КИЗ" disabled={!canPurchase || busy} onClick={() => void confirm()}>{busy ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <ShoppingCart aria-hidden="true" size={16} />}{busy ? "Запуск…" : "Подтвердить покупку КИЗ"}</button>
          ) : (
            <button className="primary-button" type="button" aria-label="Подготовить покупку" disabled={!canPurchase || busy} onClick={() => void prepare()}>{busy ? <RefreshCw aria-hidden="true" className="animate-spin" size={16} /> : <ShieldCheck aria-hidden="true" size={16} />}{busy ? "Проверка…" : "Подготовить покупку"}</button>
          )}
        </div>
      </div>
    </div>
  );
}

function validPreview(
  preview: PurchasePreview,
  shopId: number,
  gtin: string,
  quantity: number,
  version: string,
) {
  return preview.shopId === shopId
    && UUID.test(preview.purchaseId)
    && preview.gtin === gtin
    && preview.quantity === quantity
    && preview.version === version
    && typeof preview.productName === "string"
    && preview.productName.length <= 160
    && Array.isArray(preview.warnings)
    && preview.warnings.length <= PREVIEW_WARNINGS.size
    && new Set(preview.warnings).size === preview.warnings.length
    && preview.warnings.every((warning) => PREVIEW_WARNINGS.has(warning))
    && preview.autoIntroduction === preview.warnings.includes("automatic_introduction")
    && !Number.isNaN(Date.parse(preview.expiresAt));
}
