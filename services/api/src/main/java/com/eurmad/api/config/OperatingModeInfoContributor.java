package com.eurmad.api.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/** Publishes the operating mode on {@code /actuator/info} so operators can verify it. */
@Component
class OperatingModeInfoContributor implements InfoContributor {

    private final FintechProperties properties;

    OperatingModeInfoContributor(FintechProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("operatingMode", properties.mode().name());
        builder.withDetail("liveEnabled", properties.liveEnabled());
    }
}
