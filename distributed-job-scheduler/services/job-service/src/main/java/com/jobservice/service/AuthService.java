package com.jobservice.service;

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
import com.jobservice.service.interfaces.AuthServiceInterface;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService implements AuthServiceInterface {

    private static final String REGISTRATION_SUCCESS_MESSAGE = "User registered successfully";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public MessageResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(
                    ErrorCode.EMAIL_ALREADY_EXISTS,
                    "An account with this email already exists"
            );
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException(
                    ErrorCode.USERNAME_ALREADY_EXISTS,
                    "This username is already in use"
            );
        }

        User user = userMapper.toEntity(
                request,
                username,
                email,
                passwordEncoder.encode(request.password())
        );

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    ErrorCode.DATA_INTEGRITY_VIOLATION,
                    "The request conflicts with existing data"
            );
        }

        return new MessageResponse(REGISTRATION_SUCCESS_MESSAGE);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password())
            );
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String token = jwtService.generateToken(userDetails);
            return new AuthResponse(token, jwtService.getExpiresInSeconds());
        } catch (BadCredentialsException exception) {
            throw new InvalidCredentialsException();
        } catch (AuthenticationException exception) {
            throw new InvalidCredentialsException();
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
