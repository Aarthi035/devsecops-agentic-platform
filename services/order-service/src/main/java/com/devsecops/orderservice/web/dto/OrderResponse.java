package com.devsecops.orderservice.web.dto;

import com.devsecops.orderservice.domain.Order;
import com.devsecops.orderservice.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order representation returned by the REST API.
 */
public record OrderResponse(Long id,
                            Long userId,
                            Long productId,
                            int quantity,
                            BigDecimal unitPrice,
                            BigDecimal totalPrice,
                            OrderStatus status,
                            Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getProductId(),
                order.getQuantity(),
                order.getUnitPrice(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
