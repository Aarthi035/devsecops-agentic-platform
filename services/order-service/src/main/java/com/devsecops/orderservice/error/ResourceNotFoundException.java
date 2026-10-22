package com.devsecops.orderservice.error;

/**
 * Thrown when a requested resource does not exist, locally or downstream. Mapped to HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
