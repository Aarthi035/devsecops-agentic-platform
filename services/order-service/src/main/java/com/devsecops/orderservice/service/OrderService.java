package com.devsecops.orderservice.service;

import com.devsecops.orderservice.client.ProductClient;
import com.devsecops.orderservice.client.UserClient;
import com.devsecops.orderservice.client.dto.ProductDto;
import com.devsecops.orderservice.client.dto.UserDto;
import com.devsecops.orderservice.domain.Order;
import com.devsecops.orderservice.domain.OrderStatus;
import com.devsecops.orderservice.error.ResourceNotFoundException;
import com.devsecops.orderservice.repository.OrderRepository;
import com.devsecops.orderservice.web.dto.CreateOrderRequest;
import com.devsecops.orderservice.web.dto.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final ProductClient productClient;

    public OrderService(OrderRepository orderRepository, UserClient userClient, ProductClient productClient) {
        this.orderRepository = orderRepository;
        this.userClient = userClient;
        this.productClient = productClient;
    }

    /**
     * Places an order: validates the user and the available stock through the
     * other two services, then stores the order with the price snapshot.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        UserDto user = userClient.fetchUser(request.userId());
        if (!Boolean.TRUE.equals(user.active())) {
            throw new IllegalStateException("User " + request.userId() + " is inactive and cannot place orders");
        }

        ProductDto product = productClient.fetchProduct(request.productId());
        int available = product.stock() == null ? 0 : product.stock();
        if (available < request.quantity()) {
            throw new IllegalStateException("Insufficient stock for product " + request.productId()
                    + ": requested " + request.quantity() + ", available " + available);
        }

        BigDecimal unitPrice = product.price();
        BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);

        Order order = new Order(request.userId(), request.productId(), request.quantity(), unitPrice, totalPrice);
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByUser(Long userId) {
        return orderRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return OrderResponse.from(requireOrder(id));
    }

    @Transactional
    public OrderResponse confirm(Long id) {
        Order order = requireOrder(id);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new IllegalStateException("Order " + id + " cannot be confirmed from status " + order.getStatus());
        }
        order.setStatus(OrderStatus.CONFIRMED);
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = requireOrder(id);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Order " + id + " is already cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        return OrderResponse.from(orderRepository.save(order));
    }

    private Order requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " was not found"));
    }
}
