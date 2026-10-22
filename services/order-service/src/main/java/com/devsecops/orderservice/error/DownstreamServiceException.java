package com.devsecops.orderservice.error;

/**
 * Thrown when a call to user-service or product-service fails. Mapped to HTTP 502.
 */
public class DownstreamServiceException extends RuntimeException {

    public DownstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
