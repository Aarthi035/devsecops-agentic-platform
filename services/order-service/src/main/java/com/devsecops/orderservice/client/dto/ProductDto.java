package com.devsecops.orderservice.client.dto;

import java.math.BigDecimal;

/**
 * Mirrors the JSON returned by product-service: GET /api/products/{id}
 */
public record ProductDto(Long id, String name, String description, BigDecimal price, Integer stock, String category) {
}
