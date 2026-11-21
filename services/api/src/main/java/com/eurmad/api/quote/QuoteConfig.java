package com.eurmad.api.quote;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Server clock used for quote issue and expiry decisions; never the client's clock. */
@Configuration(proxyBeanMethods = false)
class QuoteConfig {

    @Bean
    Clock quoteClock() {
        return Clock.systemUTC();
    }
}
