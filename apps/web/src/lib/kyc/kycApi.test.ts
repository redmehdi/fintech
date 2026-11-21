import { describe, expect, it, vi } from "vitest";
import { Configuration, KYCApi } from "../api/generated";
import { createDashboardApis, loadCurrentCustomer, loadKycStatus, startKycVerification } from "./kycApi";

function apiReturning(response: Response) {
  const fetchApi = vi.fn<typeof fetch>().mockResolvedValue(response);
  return { fetchApi, api: new KYCApi(new Configuration({ accessToken: "synthetic-access-token", fetchApi })) };
}

function problem(status: number, correlationId = "00000000-0000-4000-8000-000000000005") {
  return Response.json(
    { code: "X", message: "server text", correlationId },
    { status, headers: { "Content-Type": "application/problem+json", "X-Correlation-Id": correlationId } },
  );
}

describe("KYC API helpers", () => {
  it("loads the caller's status", async () => {
    const { api, fetchApi } = apiReturning(Response.json({ status: "PENDING", mode: "SIMULATION" }));

    await expect(loadKycStatus(api)).resolves.toEqual({
      kind: "ok",
      value: { status: "PENDING", mode: "SIMULATION" },
    });
    expect(fetchApi).toHaveBeenCalledWith(
      "http://localhost:8080/v1/kyc/status",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("starts a session with a bearer token and no request body", async () => {
    const session = { id: "00000000-0000-4000-8000-00000000f005", status: "APPROVED", mode: "SIMULATION" };
    const { api, fetchApi } = apiReturning(Response.json(session, { status: 201 }));

    await expect(startKycVerification(api)).resolves.toEqual({ kind: "ok", value: session });
    const init = fetchApi.mock.calls[0]?.[1];
    expect(init?.method).toBe("POST");
    expect(init?.body).toBeUndefined();
    expect(init?.headers).toMatchObject({ Authorization: "Bearer synthetic-access-token" });
  });

  it.each([
    [401, "unauthenticated"],
    [403, "unauthenticated"],
    [404, "not-provisioned"],
    [409, "conflict"],
    [422, "ineligible"],
  ])("maps HTTP %i to %s", async (status, kind) => {
    const { api } = apiReturning(problem(status));
    await expect(startKycVerification(api)).resolves.toEqual({ kind });
  });

  it("surfaces only the correlation ID for server errors", async () => {
    const { api } = apiReturning(problem(500, "00000000-0000-4000-8000-000000000500"));
    await expect(loadKycStatus(api)).resolves.toEqual({
      kind: "error",
      correlationId: "00000000-0000-4000-8000-000000000500",
    });
  });

  it("treats network failures as a generic error", async () => {
    const fetchApi = vi.fn<typeof fetch>().mockRejectedValue(new TypeError("offline"));
    const api = new KYCApi(new Configuration({ fetchApi }));
    await expect(loadKycStatus(api)).resolves.toEqual({ kind: "error" });
  });

  it("routes dashboard calls through the same-origin proxy and loads the account profile", async () => {
    const customer = {
      id: "00000000-0000-4000-8000-00000000c005",
      residencyCountry: "ES",
      kycStatus: "APPROVED",
      status: "SUSPENDED",
    };
    const fetchApi = vi.fn<typeof fetch>().mockImplementation(async () => Response.json(customer));
    vi.stubGlobal("fetch", fetchApi);
    try {
      const apis = createDashboardApis("synthetic-access-token");

      await expect(loadCurrentCustomer(apis.identity)).resolves.toEqual({ kind: "ok", value: customer });
      expect(fetchApi).toHaveBeenCalledWith("/api/v1/me", expect.objectContaining({ method: "GET" }));
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
