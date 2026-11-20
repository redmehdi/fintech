package com.eurmad.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the simulation-only EUR-MAD API modular monolith.
 *
 * <p>No real payment, FX execution, custody or settlement is performed by this service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
