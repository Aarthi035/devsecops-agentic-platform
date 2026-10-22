package com.devsecops.orderservice;

import com.devsecops.orderservice.client.ProductClient;
import com.devsecops.orderservice.client.UserClient;
import com.devsecops.orderservice.client.dto.ProductDto;
import com.devsecops.orderservice.client.dto.UserDto;
import com.devsecops.orderservice.domain.Order;
import com.devsecops.orderservice.domain.OrderStatus;
import com.devsecops.orderservice.error.DownstreamServiceException;
import com.devsecops.orderservice.error.ResourceNotFoundException;
import com.devsecops.orderservice.repository.OrderRepository;
import com.devsecops.orderservice.service.OrderService;
import com.devsecops.orderservice.web.dto.CreateOrderRequest;
import com.devsecops.orderservice.web.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link OrderService}: both downstream clients and the
 * repository are mocked, so no HTTP call is ever made.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserClient userClient;

    @Mock
    private ProductClient productClient;

    @InjectMocks
    private OrderService orderService;

    private static UserDto activeUser() {
        return new UserDto(1L, "Aarthi Kumar", "aarthi@example.com", "USER", true);
    }

    private static ProductDto product(int stock) {
        return new ProductDto(10L, "Wireless Mouse", "Ergonomic mouse", new BigDecimal("19.99"), stock, "Electronics");
    }

    @Test
    void create_computesTotalPriceAndSavesOrder() {
        when(userClient.fetchUser(1L)).thenReturn(activeUser());
        when(productClient.fetchProduct(10L)).thenReturn(product(5));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.create(new CreateOrderRequest(1L, 10L, 3));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.productId()).isEqualTo(10L);
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(response.totalPrice()).isEqualByComparingTo("59.97");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalPrice()).isEqualByComparingTo("59.97");
    }

    @Test
    void create_rejectsInactiveUser() {
        when(userClient.fetchUser(2L)).thenReturn(new UserDto(2L, "Blocked User", "blocked@example.com", "USER", false));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(2L, 10L, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive");

        verify(orderRepository, never()).save(any(Order.class));
        verify(productClient, never()).fetchProduct(any());
    }

    @Test
    void create_rejectsInsufficientStock() {
        when(userClient.fetchUser(1L)).thenReturn(activeUser());
        when(productClient.fetchProduct(10L)).thenReturn(product(2));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(1L, 10L, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void create_propagatesNotFoundFromUserService() {
        when(userClient.fetchUser(404L)).thenThrow(new ResourceNotFoundException("User 404 was not found in user-service"));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(404L, 10L, 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void create_propagatesDownstreamFailure() {
        when(userClient.fetchUser(1L)).thenThrow(new DownstreamServiceException("user-service is unavailable", null));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(1L, 10L, 1)))
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void findById_returnsOrder() {
        Order order = new Order(1L, 10L, 2, new BigDecimal("19.99"), new BigDecimal("39.98"));
        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.findById(3L);

        assertThat(response.totalPrice()).isEqualByComparingTo("39.98");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void findByUser_returnsNewestFirst() {
        when(orderRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                new Order(1L, 10L, 1, new BigDecimal("19.99"), new BigDecimal("19.99"))
        ));

        List<OrderResponse> orders = orderService.findByUser(1L);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).userId()).isEqualTo(1L);
    }

    @Test
    void confirm_setsStatusConfirmed() {
        Order order = new Order(1L, 10L, 1, new BigDecimal("19.99"), new BigDecimal("19.99"));
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.confirm(5L);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void confirm_rejectsAlreadyConfirmedOrder() {
        Order order = new Order(1L, 10L, 1, new BigDecimal("19.99"), new BigDecimal("19.99"));
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.confirm(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be confirmed");
    }

    @Test
    void cancel_setsStatusCancelled() {
        Order order = new Order(1L, 10L, 1, new BigDecimal("19.99"), new BigDecimal("19.99"));
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.cancel(6L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_rejectsAlreadyCancelledOrder() {
        Order order = new Order(1L, 10L, 1, new BigDecimal("19.99"), new BigDecimal("19.99"));
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already cancelled");
    }
}
