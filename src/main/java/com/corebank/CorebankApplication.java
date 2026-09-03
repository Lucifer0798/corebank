package com.corebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling is for OutboxRelay's @Scheduled poller -- the only scheduled task in this
// application.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CorebankApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorebankApplication.class, args);
    }
}
