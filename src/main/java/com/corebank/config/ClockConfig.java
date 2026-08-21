package com.corebank.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected rather than called statically so that time-dependent rules stay testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
