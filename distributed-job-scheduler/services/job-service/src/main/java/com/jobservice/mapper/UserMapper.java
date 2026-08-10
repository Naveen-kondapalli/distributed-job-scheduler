package com.jobservice.mapper;

import com.jobservice.dto.request.RegisterRequest;
import com.jobservice.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "username", source = "username")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "password", source = "encodedPassword")
    User toEntity(RegisterRequest request, String username, String email, String encodedPassword);
}
