package com.executorservice.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class ExecutorConfig {

    @Bean
    String executorInstanceId(ExecutorProperties properties) {
        if (StringUtils.hasText(properties.getInstanceId())) {
            return properties.getInstanceId();
        }
        return hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "executor";
        }
    }
}
