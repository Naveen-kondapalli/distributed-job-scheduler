package com.jobservice.service.interfaces;

import com.jobservice.dto.request.LoginRequest;
import com.jobservice.dto.request.RegisterRequest;
import com.jobservice.dto.response.AuthResponse;
import com.jobservice.dto.response.MessageResponse;

public interface AuthServiceInterface {

    MessageResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}
