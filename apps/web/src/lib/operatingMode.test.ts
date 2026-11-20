import { describe, expect, it } from "vitest";
import { DEFAULT_OPERATING_MODE, OPERATING_MODES, resolveOperatingMode } from "./operatingMode";

describe("resolveOperatingMode", () => {
  it("defaults to SIMULATION when unset or blank", () => {
    expect(DEFAULT_OPERATING_MODE).toBe("SIMULATION");
    expect(resolveOperatingMode(undefined)).toBe("SIMULATION");
    expect(resolveOperatingMode("")).toBe("SIMULATION");
    expect(resolveOperatingMode("   ")).toBe("SIMULATION");
  });

  it("accepts explicit SIMULATION", () => {
    expect(resolveOperatingMode("SIMULATION")).toBe("SIMULATION");
  });

  it.each(["LIVE", "live", "PRODUCTION", "simulation", "SIMULATION,LIVE"])(
    "fails closed for %s",
    (value) => {
      expect(() => resolveOperatingMode(value)).toThrow(/only SIMULATION/);
    },
  );

  it("exposes SIMULATION as the only mode", () => {
    expect(OPERATING_MODES).toEqual(["SIMULATION"]);
  });
});
