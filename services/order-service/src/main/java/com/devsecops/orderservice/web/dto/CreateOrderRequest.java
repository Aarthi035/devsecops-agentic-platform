package com.devsecops.orderservice.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for placing a new order.
 */
public record CreateOrderRequest(

        @NotNull(message = "is required")
        Long userId,

        @NotNull(message = "is required")
        Long productId,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 100, message = "must be at most 100")
        Integer quantity
) {
}
