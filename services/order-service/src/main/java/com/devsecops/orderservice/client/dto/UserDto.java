package com.devsecops.orderservice.client.dto;

/**
 * Mirrors the JSON returned by user-service: GET /api/users/{id}
 */
public record UserDto(Long id, String fullName, String email, String role, Boolean active) {
}
