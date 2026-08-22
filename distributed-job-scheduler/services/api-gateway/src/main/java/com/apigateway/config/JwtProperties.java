package com.apigateway.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    private String secret;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        this.secret = secret;
    }
}
