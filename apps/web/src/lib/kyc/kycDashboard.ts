/**
 * KYC status → dashboard view model (FX-005).
 *
 * Fails closed: only an exact `APPROVED` KYC status in `SIMULATION` mode on an `ACTIVE` account
 * unlocks simulated operations, mirroring the server's eligibility rule. A non-`ACTIVE` (or unknown)
 * account locks every operation and offers no verification. Unknown statuses or modes render as
 * "unavailable" with every operation locked. Display only — the
 * API enforces eligibility server-side regardless of what the UI shows.
 */
export type KycTone = "neutral" | "pending" | "success" | "danger";

export interface KycDashboardView {
  status: string;
  title: string;
  description: string;
  tone: KycTone;
  canStartVerification: boolean;
  operationsUnlocked: boolean;
}

export const SIMULATED_OPERATIONS = [
  "Simulated EUR↔MAD transfer quotes",
  "Simulated transfers",
  "Exchange-offer matching (intent only)",
] as const;

const VIEWS: Record<string, Omit<KycDashboardView, "status">> = {
  NOT_STARTED: {
    title: "Verification not started",
    description:
      "Start the sandbox verification to unlock simulated operations. No identity documents are uploaded or stored.",
    tone: "neutral",
    canStartVerification: true,
    operationsUnlocked: false,
  },
  PENDING: {
    title: "Verification in progress",
    description: "The sandbox verification is pending. Simulated operations stay locked until it is approved.",
    tone: "pending",
    canStartVerification: false,
    operationsUnlocked: false,
  },
  APPROVED: {
    title: "Verified (sandbox)",
    description: "Your synthetic verification is approved. You can use simulated operations; no real money moves.",
    tone: "success",
    canStartVerification: false,
    operationsUnlocked: true,
  },
  REJECTED: {
    title: "Verification rejected",
    description: "Simulated operations are unavailable. Contact support for a review of this decision.",
    tone: "danger",
    canStartVerification: false,
    operationsUnlocked: false,
  },
  EXPIRED: {
    title: "Verification expired",
    description: "Your previous verification expired. Verify again to unlock simulated operations.",
    tone: "neutral",
    canStartVerification: true,
    operationsUnlocked: false,
  },
};

const UNAVAILABLE: Omit<KycDashboardView, "status"> = {
  title: "Verification status unavailable",
  description: "We could not determine your verification status. Simulated operations stay locked.",
  tone: "danger",
  canStartVerification: false,
  operationsUnlocked: false,
};

const ACCOUNT_INACTIVE: Omit<KycDashboardView, "status"> = {
  title: "Account not active",
  description: "Your account is not active, so verification and simulated operations are unavailable. Contact support.",
  tone: "danger",
  canStartVerification: false,
  operationsUnlocked: false,
};

export function describeKycStatus(status: string, mode: string, accountStatus: string): KycDashboardView {
  const known = mode === "SIMULATION" && Object.hasOwn(VIEWS, status) ? VIEWS[status] : undefined;
  if (known && accountStatus !== "ACTIVE") {
    return { status, ...ACCOUNT_INACTIVE };
  }
  return { status, ...(known ?? UNAVAILABLE) };
}
