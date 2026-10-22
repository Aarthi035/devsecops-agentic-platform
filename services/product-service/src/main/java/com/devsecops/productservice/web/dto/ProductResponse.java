package com.devsecops.productservice.web.dto;

import com.devsecops.productservice.domain.Product;

import java.math.BigDecimal;

/**
 * Product representation returned by the REST API.
 */
public record ProductResponse(Long id, String name, String description, BigDecimal price, int stock, String category) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getCategory()
        );
    }
}
