package com.harshalpatil.ecommerce.payment.payments;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway.ChargeResult;
import com.harshalpatil.ecommerce.platform.messaging.InboxProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class PaymentCommandListener {

    private final InboxProcessor inbox;
    private final PaymentService payments;

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS)
    void onCommand(ConsumerRecord<String, String> record) {
        Message message = inbox.decode(record);

        if (message instanceof ProcessPaymentCommand command) {
            if (inbox.alreadyProcessed(command)) {
                log.info("Skipping duplicate {} for order {}", command.messageId(), command.orderId());
                return;
            }
            // Gateway call happens before the transaction opens; see PaymentService.
            ChargeResult result = payments.needsCharge(command.orderId()) ? payments.charge(command) : null;
            inbox.process(command, ignored -> payments.recordCharge(command, result));
        } else if (message instanceof RefundPaymentCommand command) {
            inbox.process(command, ignored -> payments.refund(command));
        } else {
            log.warn("Ignoring unsupported {} on {}", message.getClass().getSimpleName(), record.topic());
        }
    }
}
