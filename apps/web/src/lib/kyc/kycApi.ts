import {
  Configuration,
  IdentityApi,
  KYCApi,
  ResponseError,
  type Customer,
  type KycSession,
  type KycStatus,
} from "../api/generated";

/**
 * Browser base path for API calls: the web origin's same-origin proxy (see `next.config.ts`), so
 * bearer-authenticated requests need no cross-origin preflight.
 */
export const BROWSER_API_BASE_PATH = "/api";

export interface DashboardApis {
  kyc: KYCApi;
  identity: IdentityApi;
}

export function createDashboardApis(accessToken: string): DashboardApis {
  const configuration = new Configuration({ basePath: BROWSER_API_BASE_PATH, accessToken });
  return { kyc: new KYCApi(configuration), identity: new IdentityApi(configuration) };
}

/** Outcome of a KYC API call, with failures mapped to client-safe kinds (no server text shown). */
export type KycResult<T> =
  | { kind: "ok"; value: T }
  | { kind: "unauthenticated" }
  | { kind: "not-provisioned" }
  | { kind: "conflict" }
  | { kind: "ineligible" }
  | { kind: "error"; correlationId?: string };

async function toFailure(error: unknown): Promise<Exclude<KycResult<never>, { kind: "ok" }>> {
  if (!(error instanceof ResponseError)) {
    return { kind: "error" };
  }
  switch (error.response.status) {
    case 401:
    case 403:
      return { kind: "unauthenticated" };
    case 404:
      return { kind: "not-provisioned" };
    case 409:
      return { kind: "conflict" };
    case 422:
      return { kind: "ineligible" };
    default: {
      const correlationId = error.response.headers.get("X-Correlation-Id") ?? undefined;
      return { kind: "error", correlationId };
    }
  }
}

async function run<T>(call: () => Promise<T>): Promise<KycResult<T>> {
  try {
    return { kind: "ok", value: await call() };
  } catch (error: unknown) {
    return toFailure(error);
  }
}

export function loadKycStatus(api: KYCApi): Promise<KycResult<KycStatus>> {
  return run(() => api.getKycStatus());
}

/** The caller's own profile; its account `status` gates eligibility together with KYC. */
export function loadCurrentCustomer(api: IdentityApi): Promise<KycResult<Customer>> {
  return run(() => api.getCurrentCustomer());
}

/** Starts a sandbox verification. Sends no request body: documents are never collected here. */
export function startKycVerification(api: KYCApi): Promise<KycResult<KycSession>> {
  return run(() => api.createKycSession());
}
