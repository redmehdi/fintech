import { describe, expect, it } from "vitest";
import { describeKycStatus } from "./kycDashboard";

describe("describeKycStatus", () => {
  it("unlocks simulated operations only for APPROVED in SIMULATION mode", () => {
    expect(describeKycStatus("APPROVED", "SIMULATION", "ACTIVE")).toMatchObject({
      operationsUnlocked: true,
      canStartVerification: false,
      tone: "success",
    });
  });

  it.each(["NOT_STARTED", "PENDING", "REJECTED", "EXPIRED"])("keeps operations locked for %s", (status) => {
    expect(describeKycStatus(status, "SIMULATION", "ACTIVE").operationsUnlocked).toBe(false);
  });

  it("offers verification only from NOT_STARTED or EXPIRED", () => {
    const startable = ["NOT_STARTED", "PENDING", "APPROVED", "REJECTED", "EXPIRED"].filter(
      (status) => describeKycStatus(status, "SIMULATION", "ACTIVE").canStartVerification,
    );
    expect(startable).toEqual(["NOT_STARTED", "EXPIRED"]);
  });

  it.each([
    ["approved", "SIMULATION"],
    ["toString", "SIMULATION"],
    ["APPROVED", "LIVE"],
    ["", ""],
  ])("fails closed for unknown status %j / mode %j", (status, mode) => {
    expect(describeKycStatus(status, mode, "ACTIVE")).toMatchObject({
      title: "Verification status unavailable",
      operationsUnlocked: false,
      canStartVerification: false,
    });
  });

  it.each(["SUSPENDED", "CLOSED", "", "active"])("locks everything for non-ACTIVE account %j", (accountStatus) => {
    for (const status of ["NOT_STARTED", "PENDING", "APPROVED", "REJECTED", "EXPIRED"]) {
      expect(describeKycStatus(status, "SIMULATION", accountStatus)).toMatchObject({
        status,
        title: "Account not active",
        operationsUnlocked: false,
        canStartVerification: false,
      });
    }
  });
});
