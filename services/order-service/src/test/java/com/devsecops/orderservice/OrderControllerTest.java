package com.devsecops.orderservice;

import com.devsecops.orderservice.domain.OrderStatus;
import com.devsecops.orderservice.error.ResourceNotFoundException;
import com.devsecops.orderservice.service.OrderService;
import com.devsecops.orderservice.web.OrderController;
import com.devsecops.orderservice.web.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link OrderController}. The service is mocked, so no
 * downstream HTTP call happens and no database is needed.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private static OrderResponse sampleOrder() {
        return new OrderResponse(7L, 1L, 10L, 2, new BigDecimal("19.99"), new BigDecimal("39.98"),
                OrderStatus.CREATED, Instant.parse("2026-01-01T10:15:30Z"));
    }

    @Test
    void createOrder_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{userId:1,\"productId\":10,\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void createOrder_returns201WithLocation() throws Exception {
        when(orderService.create(any())).thenReturn(sampleOrder());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", 1,
                                "productId", 10,
                                "quantity", 2))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.totalPrice").value(39.98))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void createOrder_withZeroQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", 1,
                                "productId", 10,
                                "quantity", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quantity")));
    }

    @Test
    void createOrder_withMissingUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", 10,
                                "quantity", 2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")));
    }

    @Test
    void getUnknownOrder_returns404() throws Exception {
        when(orderService.findById(999L)).thenThrow(new ResourceNotFoundException("Order 999 was not found"));

        mockMvc.perform(get("/api/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order 999 was not found"))
                .andExpect(jsonPath("$.path").value("/api/orders/999"));
    }

    @Test
    void listOrders_returnsArray() throws Exception {
        when(orderService.findAll()).thenReturn(List.of(sampleOrder()));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    void listOrdersByUser_usesFilter() throws Exception {
        when(orderService.findByUser(eq(1L))).thenReturn(List.of(sampleOrder()));

        mockMvc.perform(get("/api/orders").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(1));
    }

    @Test
    void confirmOrder_returnsUpdatedStatus() throws Exception {
        OrderResponse confirmed = new OrderResponse(7L, 1L, 10L, 2, new BigDecimal("19.99"),
                new BigDecimal("39.98"), OrderStatus.CONFIRMED, Instant.parse("2026-01-01T10:15:30Z"));
        when(orderService.confirm(7L)).thenReturn(confirmed);

        mockMvc.perform(post("/api/orders/7/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void cancelOrder_returnsUpdatedStatus() throws Exception {
        OrderResponse cancelled = new OrderResponse(7L, 1L, 10L, 2, new BigDecimal("19.99"),
                new BigDecimal("39.98"), OrderStatus.CANCELLED, Instant.parse("2026-01-01T10:15:30Z"));
        when(orderService.cancel(7L)).thenReturn(cancelled);

        mockMvc.perform(post("/api/orders/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
