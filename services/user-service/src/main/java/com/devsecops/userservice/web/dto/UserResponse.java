package com.devsecops.userservice.web.dto;

import com.devsecops.userservice.domain.User;

/**
 * User representation returned by the REST API.
 */
public record UserResponse(Long id, String fullName, String email, String role, boolean active) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.isActive()
        );
    }
}
