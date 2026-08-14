package com.watchservice.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock clock() {
        return Clock.system(APPLICATION_ZONE);
    }
}
