package com.eurmad.api.config;

/**
 * Application-wide operating mode.
 *
 * <p>{@link #SIMULATION} is intentionally the only value. A live mode must not be added until the
 * legal/partner milestones (FX-019, FX-020) are signed off; it can never be enabled by
 * configuration alone. Any other configured value fails application startup (fail closed).
 */
public enum OperatingMode {
    SIMULATION
}
