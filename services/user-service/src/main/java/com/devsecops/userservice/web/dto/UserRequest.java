package com.devsecops.userservice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating or updating a user.
 */
public record UserRequest(

        @NotBlank(message = "is required")
        @Size(min = 2, max = 120, message = "must be between 2 and 120 characters")
        String fullName,

        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        @Size(max = 180, message = "must be at most 180 characters")
        String email,

        @NotBlank(message = "is required")
        @Pattern(regexp = "USER|ADMIN|SUPPORT", message = "must be one of USER, ADMIN, SUPPORT")
        String role
) {
}
