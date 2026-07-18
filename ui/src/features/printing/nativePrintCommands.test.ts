import { invoke } from "jdesk-client";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ExportSupplyRequest } from "../../generated/types";
import { exportSupplyPdf, NATIVE_PRINT_EXPORT_TIMEOUT_MS } from "./nativePrintCommands";

vi.mock("jdesk-client", () => ({
  invoke: vi.fn(),
}));

const invokeCommand = vi.mocked(invoke);

describe("nativePrintCommands", () => {
  beforeEach(() => {
    invokeCommand.mockReset();
  });

  it("keeps the bridge alive while the user completes the native save dialog", async () => {
    const request: ExportSupplyRequest = {
      shopId: 8,
      supplyId: "WB-GI-244638998",
      query: "",
      sort: { bySubject: true, byArticle: true, byColor: true, bySize: true },
      pageOrder: "barcode_then_sticker",
      barcodeCopies: 1,
    };
    const response = {
      cancelled: false,
      exportId: "9a59c3c2-55dc-4bb1-90e7-3b5dba0eaa43",
      labelsFileName: "WCODE-WB-GI-244638998.pdf",
      detailsFileName: "NHAT_HANG-WCODE-WB-GI-244638998.pdf",
      printJobId: "35",
      itemCount: 5,
      pageCount: 10,
      kizAttachmentCount: 0,
    };
    invokeCommand.mockResolvedValue(response);

    await expect(exportSupplyPdf(request)).resolves.toEqual(response);
    expect(NATIVE_PRINT_EXPORT_TIMEOUT_MS).toBe(10 * 60 * 1_000);
    expect(invokeCommand).toHaveBeenCalledWith(
      "printing.exportSupply",
      request,
      { timeoutMs: NATIVE_PRINT_EXPORT_TIMEOUT_MS },
    );
  });
});
