package com.jobservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;
    private UserPrincipal userPrincipal;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-minimum-32-characters");
        properties.setExpirationMs(3_600_000);
        jwtService = new JwtService(properties);
        userPrincipal = new UserPrincipal(1L, "naveen", "naveen@example.com", "$2a$hash");
    }

    @Test
    void generatedTokenContainsCorrectSubject() {
        String token = jwtService.generateToken(userPrincipal);

        assertThat(jwtService.extractEmail(token)).isEqualTo("naveen@example.com");
    }

    @Test
    void validTokenPassesValidation() {
        String token = jwtService.generateToken(userPrincipal);

        assertThat(jwtService.isTokenValid(token, userPrincipal)).isTrue();
    }

    @Test
    void invalidTokenFailsValidationSafely() {
        assertThat(jwtService.isTokenValid("not-a-valid-token", userPrincipal)).isFalse();
        assertThat(jwtService.extractEmail("not-a-valid-token")).isNull();
    }
}
