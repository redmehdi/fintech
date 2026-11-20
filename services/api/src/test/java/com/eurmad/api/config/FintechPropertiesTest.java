package com.eurmad.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Fail-closed behaviour of the product safety configuration. */
class FintechPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FintechProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsToSimulationWhenUnset() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    FintechProperties props = ctx.getBean(FintechProperties.class);
                    assertThat(props.mode()).isEqualTo(OperatingMode.SIMULATION);
                    assertThat(props.liveEnabled()).isFalse();
                });
    }

    @Test
    void explicitSimulationIsAccepted() {
        runner.withPropertyValues("fintech.mode=SIMULATION", "fintech.live-enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void liveModeFailsStartup() {
        runner.withPropertyValues("fintech.mode=LIVE").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void productionModeFailsStartup() {
        runner.withPropertyValues("fintech.mode=PRODUCTION")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void liveEnabledFlagFailsStartup() {
        runner.withPropertyValues("fintech.live-enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(ctx.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("live-enabled");
                        });
    }

    @Test
    void constructorRejectsNullMode() {
        assertThatThrownBy(() -> new FintechProperties(null, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enumHasOnlySimulation() {
        assertThat(OperatingMode.values()).containsExactly(OperatingMode.SIMULATION);
    }
}
