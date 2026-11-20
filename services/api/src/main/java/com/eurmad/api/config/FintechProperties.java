package com.eurmad.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level product safety configuration bound from {@code fintech.*}.
 *
 * <p>Fails closed: startup is aborted if the mode is anything other than {@link
 * OperatingMode#SIMULATION} (unknown enum values fail binding) or if {@code fintech.live-enabled}
 * is set to {@code true}. Live operation cannot be activated by configuration in the MVP.
 *
 * @param mode operating mode; defaults to {@link OperatingMode#SIMULATION}
 * @param liveEnabled must remain {@code false}; present only so an attempt to enable it is rejected
 *     loudly instead of being silently ignored
 */
@ConfigurationProperties(prefix = "fintech")
public record FintechProperties(
        @DefaultValue("SIMULATION") OperatingMode mode,
        @DefaultValue("false") boolean liveEnabled) {

    public FintechProperties {
        if (mode != OperatingMode.SIMULATION) {
            throw new IllegalStateException(
                    "fintech.mode must be SIMULATION; live operation is not available in this MVP");
        }
        if (liveEnabled) {
            throw new IllegalStateException(
                    "fintech.live-enabled=true is not permitted; live money movement requires a"
                            + " separately reviewed legal and licensed-provider milestone");
        }
    }
}
