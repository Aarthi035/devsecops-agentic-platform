package com.devsecops.productservice.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload for creating or updating a product.
 */
public record ProductRequest(

        @NotBlank(message = "is required")
        @Size(min = 2, max = 150, message = "must be between 2 and 150 characters")
        String name,

        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @NotNull(message = "is required")
        @DecimalMin(value = "0.00", message = "must be greater than or equal to 0.00")
        @Digits(integer = 10, fraction = 2, message = "must have at most 10 integer digits and 2 fraction digits")
        BigDecimal price,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be greater than or equal to 0")
        Integer stock,

        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String category
) {
}
