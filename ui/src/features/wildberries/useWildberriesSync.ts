import { useCallback, useEffect, useRef, useState } from "react";
import { commands } from "../../generated/commands";
import type { SyncStatusResponse } from "../../generated/types";

export type WildberriesSyncState =
  | { status: "idle" }
  | { status: "starting" }
  | { status: "running" }
  | { status: "cancelling" }
  | { status: "completed"; result: SyncStatusResponse }
  | { status: "cancelled" }
  | { status: "failed"; errorKind: string; retryable: boolean };

export interface WildberriesSyncController {
  state: WildberriesSyncState;
  start: () => Promise<void>;
  cancel: () => Promise<void>;
}

const pollIntervalMs = 500;
const idleState: WildberriesSyncState = { status: "idle" };

export function useWildberriesSync(
  shopId: number | null,
  onCompleted: (shopId: number) => Promise<void>,
): WildberriesSyncController {
  const [storedState, setStoredState] = useState<{
    shopId: number | null;
    state: WildberriesSyncState;
  }>({ shopId, state: idleState });
  const request = useRef(0);
  const activeJob = useRef<{ shopId: number; jobId: string } | null>(null);
  const state = storedState.shopId === shopId ? storedState.state : idleState;

  useEffect(() => {
    request.current += 1;
    activeJob.current = null;
  }, [shopId]);

  useEffect(
    () => () => {
      request.current += 1;
    },
    [],
  );

  const start = useCallback(async () => {
    if (shopId === null) return;
    const requestId = ++request.current;
    setStoredState({ shopId, state: { status: "starting" } });
    try {
      const started = await commands.wildberries.syncOverview({ shopId });
      if (request.current !== requestId) return;
      activeJob.current = { shopId, jobId: started.jobId };
      setStoredState({ shopId, state: { status: "running" } });

      while (request.current === requestId) {
        const status = await commands.wildberries.syncStatus({ shopId, jobId: started.jobId });
        if (request.current !== requestId) return;
        if (status.state === "running") {
          await delay(pollIntervalMs);
          continue;
        }
        activeJob.current = null;
        if (status.state === "completed") {
          setStoredState({ shopId, state: { status: "completed", result: status } });
          await onCompleted(shopId);
        } else if (status.state === "cancelled") {
          setStoredState({ shopId, state: { status: "cancelled" } });
        } else {
          setStoredState({
            shopId,
            state: {
              status: "failed",
              errorKind: status.errorKind || "internal",
              retryable: status.retryable,
            },
          });
        }
        return;
      }
    } catch {
      if (request.current === requestId) {
        activeJob.current = null;
        setStoredState({
          shopId,
          state: { status: "failed", errorKind: "unavailable", retryable: true },
        });
      }
    }
  }, [onCompleted, shopId]);

  const cancel = useCallback(async () => {
    const job = activeJob.current;
    if (job === null) return;
    setStoredState({ shopId: job.shopId, state: { status: "cancelling" } });
    try {
      await commands.wildberries.cancelSync(job);
    } catch {
      setStoredState({
        shopId: job.shopId,
        state: { status: "failed", errorKind: "unavailable", retryable: true },
      });
    }
  }, []);

  return { state, start, cancel };
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
