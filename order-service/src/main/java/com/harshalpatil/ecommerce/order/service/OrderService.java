package com.harshalpatil.ecommerce.order.service;

import com.harshalpatil.ecommerce.common.enums.OrderStatus;
import com.harshalpatil.ecommerce.common.event.OrderCreatedEvent;
import com.harshalpatil.ecommerce.common.event.OrderItemDto;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.order.client.ProductClient;
import com.harshalpatil.ecommerce.order.client.ProductDto;
import com.harshalpatil.ecommerce.order.dto.*;
import com.harshalpatil.ecommerce.order.entity.Order;
import com.harshalpatil.ecommerce.order.entity.OrderItem;
import com.harshalpatil.ecommerce.order.exception.OrderNotFoundException;
import com.harshalpatil.ecommerce.order.exception.ProductUnavailableException;
import com.harshalpatil.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public OrderResponse createOrder(Long customerId, CreateOrderRequest request) {
        Order order = Order.builder()
                .customerId(customerId)
                .status(OrderStatus.CREATED)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.getItems()) {
            ProductDto product = fetchProduct(itemRequest.getProductId());
            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            total = total.add(lineTotal);

            order.addItem(OrderItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(product.getPrice())
                    .build());
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        publishOrderCreated(saved);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void updateStatus(Long orderId, OrderStatus status) {
        Order order = getOrThrow(orderId);
        order.setStatus(status);
        orderRepository.save(order);
        log.info("Order {} moved to status {}", orderId, status);
    }

    private ProductDto fetchProduct(Long productId) {
        try {
            ProductDto product = productClient.getProduct(productId);
            if (product == null || product.getId() == null) {
                throw new ProductUnavailableException(productId);
            }
            return product;
        } catch (Exception ex) {
            throw new ProductUnavailableException(productId);
        }
    }

    private void publishOrderCreated(Order order) {
        List<OrderItemDto> items = order.getItems().stream()
                .map(i -> OrderItemDto.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build())
                .toList();

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .items(items)
                .totalAmount(order.getTotalAmount())
                .createdAt(Instant.now())
                .build();

        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, String.valueOf(order.getId()), event);
        log.info("Published OrderCreatedEvent for order {}", order.getId());
    }

    private Order getOrThrow(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> OrderItemResponse.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build())
                .toList();

        return OrderResponse.builder()
                .id(order.getId())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
