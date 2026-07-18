import { invoke } from "jdesk-client";
import type { ExportSupplyRequest, PrintExportResponse } from "../../generated/types";

// The bridge default is 30 seconds. This command deliberately includes a native
// save panel, so its deadline must also cover the user's folder selection time.
export const NATIVE_PRINT_EXPORT_TIMEOUT_MS = 10 * 60 * 1_000;

export function exportSupplyPdf(request: ExportSupplyRequest): Promise<PrintExportResponse> {
  return invoke(
    "printing.exportSupply",
    request,
    { timeoutMs: NATIVE_PRINT_EXPORT_TIMEOUT_MS },
  ) as Promise<PrintExportResponse>;
}
