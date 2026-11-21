"use client";

import { useEffect, useMemo, useState } from "react";
import { getAccessToken } from "@/lib/auth/accessToken";
import { describeKycStatus, SIMULATED_OPERATIONS } from "@/lib/kyc/kycDashboard";
import {
  createDashboardApis,
  loadCurrentCustomer,
  loadKycStatus,
  startKycVerification,
  type DashboardApis,
  type KycResult,
} from "@/lib/kyc/kycApi";

type State =
  | { phase: "loading" }
  | { phase: "signed-out" }
  | { phase: "loaded"; status: string; mode: string; accountStatus: string; notice?: string }
  | { phase: "failed"; message: string };

function failureMessage(result: Exclude<KycResult<unknown>, { kind: "ok" }>): string {
  switch (result.kind) {
    case "unauthenticated":
      return "Please sign in again.";
    case "not-provisioned":
      return "Your customer profile has not been set up yet.";
    case "conflict":
      return "A verification cannot be started in your current status.";
    case "ineligible":
      return "Your account is not eligible to start a verification.";
    case "error":
      return result.correlationId
        ? `Something went wrong. Reference: ${result.correlationId}`
        : "Something went wrong. Please try again.";
  }
}

/** Loads KYC status and account status together; eligibility needs both. */
async function loadState(apis: DashboardApis, notice?: string): Promise<State> {
  const [kyc, customer] = await Promise.all([loadKycStatus(apis.kyc), loadCurrentCustomer(apis.identity)]);
  if (kyc.kind !== "ok") return { phase: "failed", message: failureMessage(kyc) };
  if (customer.kind !== "ok") return { phase: "failed", message: failureMessage(customer) };
  return {
    phase: "loaded",
    status: kyc.value.status,
    mode: kyc.value.mode,
    accountStatus: customer.value.status,
    notice,
  };
}

export function KycDashboard() {
  const accessToken = getAccessToken();
  const api = useMemo(() => (accessToken ? createDashboardApis(accessToken) : null), [accessToken]);
  const [state, setState] = useState<State>(() => (api ? { phase: "loading" } : { phase: "signed-out" }));
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    void loadState(api).then((next) => {
      if (!cancelled) setState(next);
    });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const start = async () => {
    if (!api || starting) return;
    setStarting(true);
    const started = await startKycVerification(api.kyc);
    const next = await loadState(api, started.kind === "ok" ? undefined : failureMessage(started));
    setStarting(false);
    setState(next);
  };

  if (state.phase === "signed-out") {
    return (
      <section aria-labelledby="kyc-heading">
        <h2 id="kyc-heading">Identity verification</h2>
        <p>Sign in to see your verification status. Simulated operations are locked until you are verified.</p>
      </section>
    );
  }
  if (state.phase === "loading") {
    return <p role="status">Loading verification status…</p>;
  }
  if (state.phase === "failed") {
    return <p role="alert">{state.message}</p>;
  }

  const view = describeKycStatus(state.status, state.mode, state.accountStatus);
  return (
    <section aria-labelledby="kyc-heading" data-kyc-status={view.status} data-tone={view.tone}>
      <h2 id="kyc-heading">Identity verification · Sandbox</h2>
      <p className={`kyc-status kyc-status--${view.tone}`}>
        <strong>{view.title}</strong>
      </p>
      <p>{view.description}</p>
      {state.notice ? <p role="alert">{state.notice}</p> : null}
      {view.canStartVerification ? (
        <button type="button" onClick={start} disabled={starting} aria-busy={starting}>
          {starting ? "Verifying…" : "Start sandbox verification"}
        </button>
      ) : null}
      <h3>Simulated operations</h3>
      <ul>
        {SIMULATED_OPERATIONS.map((operation) => (
          <li key={operation}>
            {operation} — {view.operationsUnlocked ? "available (simulation only)" : "locked"}
          </li>
        ))}
      </ul>
    </section>
  );
}
