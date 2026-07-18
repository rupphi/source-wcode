import { useCallback, useEffect, useRef, useState } from "react";
import { JDeskError } from "jdesk-client";
import { commands } from "../../generated/commands";
import type { SupplyRefreshStatusResponse } from "../../generated/types";

export type SupplyRefreshState =
  | { status: "idle" }
  | { status: "starting" }
  | { status: "running" }
  | { status: "cancelling" }
  | { status: "completed"; result: SupplyRefreshStatusResponse }
  | { status: "cancelled" }
  | { status: "failed"; errorKind: string; retryable: boolean };

export interface SupplyRefreshController {
  state: SupplyRefreshState;
  start: () => Promise<void>;
  cancel: () => Promise<void>;
}

const POLL_INTERVAL_MS = 500;
const IDLE_STATE: SupplyRefreshState = { status: "idle" };

export function useSupplyRefresh(
  shopId: number,
  supplyId: string,
  reloadLocal: () => Promise<void>,
): SupplyRefreshController {
  const [storedState, setStoredState] = useState<{
    shopId: number;
    supplyId: string;
    state: SupplyRefreshState;
  }>({ shopId, supplyId, state: IDLE_STATE });
  const request = useRef(0);
  const activeJob = useRef<{ shopId: number; supplyId: string; jobId: string } | null>(null);
  const state = storedState.shopId === shopId && storedState.supplyId === supplyId
    ? storedState.state
    : IDLE_STATE;

  useEffect(() => {
    request.current += 1;
    activeJob.current = null;
  }, [shopId, supplyId]);

  useEffect(
    () => () => {
      request.current += 1;
    },
    [],
  );

  const start = useCallback(async () => {
    const requestId = ++request.current;
    setStoredState({ shopId, supplyId, state: { status: "starting" } });
    try {
      const started = await commands.supplies.refresh({ shopId, supplyId });
      if (request.current !== requestId) return;
      if (started.shopId !== shopId || started.supplyId !== supplyId) {
        throw new Error("Mismatched supply refresh response");
      }
      activeJob.current = { shopId, supplyId, jobId: started.jobId };
      setStoredState({ shopId, supplyId, state: { status: "running" } });

      while (request.current === requestId) {
        const status = await commands.supplies.refreshStatus({ shopId, supplyId, jobId: started.jobId });
        if (request.current !== requestId) return;
        if (status.shopId !== shopId || status.supplyId !== supplyId || status.jobId !== started.jobId) {
          throw new Error("Mismatched supply refresh status");
        }
        if (status.state === "running") {
          await delay(POLL_INTERVAL_MS);
          continue;
        }

        activeJob.current = null;
        await reloadLocal();
        if (request.current !== requestId) return;
        if (status.state === "completed") {
          setStoredState({ shopId, supplyId, state: { status: "completed", result: status } });
        } else if (status.state === "cancelled") {
          setStoredState({ shopId, supplyId, state: { status: "cancelled" } });
        } else {
          setStoredState({
            shopId,
            supplyId,
            state: {
              status: "failed",
              errorKind: status.errorKind || "internal",
              retryable: status.retryable,
            },
          });
        }
        return;
      }
    } catch (error) {
      if (request.current === requestId) {
        activeJob.current = null;
        await reloadLocal();
        if (request.current === requestId) {
          const failure = safeRefreshError(error);
          setStoredState({
            shopId,
            supplyId,
            state: { status: "failed", errorKind: failure.kind, retryable: failure.retryable },
          });
        }
      }
    }
  }, [reloadLocal, shopId, supplyId]);

  const cancel = useCallback(async () => {
    const job = activeJob.current;
    if (job === null) return;
    setStoredState({
      shopId: job.shopId,
      supplyId: job.supplyId,
      state: { status: "cancelling" },
    });
    try {
      await commands.supplies.cancelRefresh(job);
    } catch {
      setStoredState({
        shopId: job.shopId,
        supplyId: job.supplyId,
        state: { status: "failed", errorKind: "unavailable", retryable: true },
      });
    }
  }, []);

  return { state, start, cancel };
}

const ALLOWED_ERROR_KINDS = new Set([
  "cancelled",
  "internal",
  "invalid_job",
  "invalid_shop",
  "invalid_supply",
  "job_not_found",
  "rate_limited",
  "shop_busy",
  "shop_not_found",
  "supply_not_found",
  "token_invalid",
  "token_missing",
  "unavailable",
  "upstream",
]);

function safeRefreshError(error: unknown): { kind: string; retryable: boolean } {
  if (!(error instanceof JDeskError) || error.data === null || typeof error.data !== "object") {
    return { kind: "unavailable", retryable: true };
  }
  const data = error.data as { kind?: unknown; retryable?: unknown };
  if (typeof data.kind !== "string" || !ALLOWED_ERROR_KINDS.has(data.kind)) {
    return { kind: "unavailable", retryable: true };
  }
  return { kind: data.kind, retryable: data.retryable === true };
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
