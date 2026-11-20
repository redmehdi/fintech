/**
 * Product operating mode for the web client.
 *
 * SIMULATION is the only supported mode in the MVP. The UI never enables live money movement;
 * any other configured value is rejected (fail closed) rather than silently accepted.
 */
export const OPERATING_MODES = ["SIMULATION"] as const;
export type OperatingMode = (typeof OPERATING_MODES)[number];

export const DEFAULT_OPERATING_MODE: OperatingMode = "SIMULATION";

export function resolveOperatingMode(raw: string | undefined): OperatingMode {
  const value = raw?.trim();
  if (value === undefined || value === "") {
    return DEFAULT_OPERATING_MODE;
  }
  if ((OPERATING_MODES as readonly string[]).includes(value)) {
    return value as OperatingMode;
  }
  throw new Error(
    `Unsupported operating mode "${value}": only SIMULATION is permitted in this MVP`,
  );
}

export const operatingMode: OperatingMode = resolveOperatingMode(
  process.env.NEXT_PUBLIC_OPERATING_MODE,
);
