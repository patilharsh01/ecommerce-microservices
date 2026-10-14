package com.harshalpatil.ecommerce.payment.messaging;

import com.harshalpatil.ecommerce.common.event.StockReservedEvent;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockEventsListener {

    private final PaymentService paymentService;

    @KafkaListener(topics = KafkaTopics.STOCK_RESERVED, groupId = "payment-service")
    public void onStockReserved(StockReservedEvent event) {
        log.info("Processing payment for order {}", event.getOrderId());
        paymentService.process(event);
    }
}
