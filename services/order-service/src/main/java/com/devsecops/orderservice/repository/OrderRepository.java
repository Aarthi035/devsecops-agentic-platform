package com.devsecops.orderservice.repository;

import com.devsecops.orderservice.domain.Order;
import com.devsecops.orderservice.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserIdOrderByIdDesc(Long userId);

    List<Order> findByStatus(OrderStatus status);
}
