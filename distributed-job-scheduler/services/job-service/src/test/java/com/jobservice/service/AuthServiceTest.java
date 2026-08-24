package com.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobservice.dto.request.LoginRequest;
import com.jobservice.dto.request.RegisterRequest;
import com.jobservice.dto.response.AuthResponse;
import com.jobservice.dto.response.MessageResponse;
import com.jobservice.entity.User;
import com.jobservice.exception.ConflictException;
import com.jobservice.exception.ErrorCode;
import com.jobservice.exception.InvalidCredentialsException;
import com.jobservice.mapper.UserMapper;
import com.jobservice.repository.UserRepository;
import com.jobservice.security.JwtService;
import com.jobservice.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private Authentication authentication;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager, jwtService, userMapper);
    }

    @Test
    void registerCreatesUserSuccessfully() {
        when(passwordEncoder.encode("password123")).thenReturn("$2a$hash");

        MessageResponse response = authService.register(
                new RegisterRequest(" naveen ", " NAVEEN@example.COM ", "password123")
        );

        assertThat(response.message()).isEqualTo("User registered successfully");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerHashesPasswordAndNormalizesEmail() {
        when(passwordEncoder.encode("password123")).thenReturn("$2a$hash");
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        authService.register(new RegisterRequest("naveen", " NAVEEN@example.COM ", "password123"));

        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("naveen@example.com");
        assertThat(savedUser.getPassword()).isEqualTo("$2a$hash");
        assertThat(savedUser.getPassword()).isNotEqualTo("password123");
    }

    @Test
    void registerDuplicateEmailReturnsConflict() {
        when(userRepository.existsByEmailIgnoreCase("naveen@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("naveen", "naveen@example.com", "password123")
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void registerDuplicateUsernameReturnsConflict() {
        when(userRepository.existsByUsernameIgnoreCase("naveen")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("naveen", "naveen@example.com", "password123")
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS);
    }

    @Test
    void loginReturnsJwtOnSuccessfulAuthentication() {
        UserPrincipal principal = new UserPrincipal(1L, "naveen", "naveen@example.com", "$2a$hash");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtService.generateToken(principal)).thenReturn("jwt-token");
        when(jwtService.getExpiresInSeconds()).thenReturn(3600L);

        AuthResponse response = authService.login(new LoginRequest(" NAVEEN@example.COM ", "password123"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(3600L);
    }

    @Test
    void loginInvalidCredentialsReturnsInvalidCredentialsException() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("naveen@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
